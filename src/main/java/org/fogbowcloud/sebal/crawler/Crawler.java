package org.fogbowcloud.sebal.crawler;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.fogbowcloud.sebal.FMask;
import org.fogbowcloud.sebal.ImageData;
import org.fogbowcloud.sebal.ImageDataStore;
import org.fogbowcloud.sebal.ImageState;
import org.fogbowcloud.sebal.JDBCImageDataStore;
import org.fogbowcloud.sebal.NASARepository;
import org.mapdb.DB;
import org.mapdb.DBMaker;

public class Crawler {

	//FIXME: FTP - 0 (In infrastructure creation?)
	//FIXME: test - 1
	private final Properties properties;
	private NASARepository NASARepository;
	private final ImageDataStore imageStore;
	private File pendingImageDownloadFile;
	private DB pendingImageDownloadDB;
	private ConcurrentMap<String, ImageData> pendingImageDownloadMap;
	private String federationMember;

	private static final long DEFAULT_SCHEDULER_PERIOD = 300000; // 5 minutes
	// Image dir size in bytes
	private static final long DEFAULT_IMAGE_DIR_SIZE = 356 * FileUtils.ONE_MB;

	public static final Logger LOGGER = Logger.getLogger(Crawler.class);

	public Crawler(Properties properties, String imageStoreIP,
			String imageStorePort, String federationMember) {

		if (properties == null) {
			throw new IllegalArgumentException(
					"Properties arg must not be null.");
		}

		this.properties = properties;
		this.imageStore = new JDBCImageDataStore(properties, imageStoreIP,
				imageStorePort);
		this.NASARepository = new NASARepository(properties);
		
		this.federationMember = federationMember;

		if(federationMember == null || federationMember.isEmpty()) {			
			LOGGER.error("Federation member field is empty!");
			return;
		}
		
		this.pendingImageDownloadFile = new File("pending-image-download.db");
		this.pendingImageDownloadDB = DBMaker.newFileDB(pendingImageDownloadFile).make();
		
		if(!pendingImageDownloadFile.exists() || !pendingImageDownloadFile.isFile()) {			
			this.pendingImageDownloadMap = pendingImageDownloadDB.createHashMap("map").make();
		} else {
			this.pendingImageDownloadMap = pendingImageDownloadDB.getHashMap("map");
		}
	}

	public void exec() throws InterruptedException, IOException {

		LOGGER.info("Initializing crawler... ");
		
		cleanUnfinishedDownloadedData();

		try {
			do {
				deleteFetchedResultsFromVolume(properties);

				long numToDownload = numberOfImagesToDownload();
				if (numToDownload > 0) {
					download(numToDownload);
				} else {
					Thread.sleep(DEFAULT_SCHEDULER_PERIOD);
				}

			} while (thereIsImageToDownload());
		} catch (Throwable e) {
			LOGGER.error("Failed while download task.", e);
			e.printStackTrace();
		}

		pendingImageDownloadDB.close();
		LOGGER.debug("All images downloaded.\nProcess finished.");
	}
	
	private void cleanUnfinishedDownloadedData() {
		Collection<ImageData> data = pendingImageDownloadMap.values();
		for(ImageData iData : data) {
			removeFromPendingAndUpdateState(iData);
		}
	}

	private boolean thereIsImageToDownload() throws SQLException {
		List<ImageData> imageData = imageStore.getIn(ImageState.NOT_DOWNLOADED);
		return !imageData.isEmpty();
	}

	protected void download(long maxImagesToDownload) throws SQLException {

		// FIXME: check the implications of this cast
		// This updates images in NOT_DOWNLOADED state to DOWNLOADING 
		// and sets this federation member as owner, and then gets all images marked as DOWNLOADING
		List<ImageData> imageDataList = imageStore.getImagesToDownload(
				federationMember, (int) maxImagesToDownload); 

		for (ImageData imageData : imageDataList) {
			if (imageData != null) {	
				if (imageStore.lockImage(imageData.getName())) {
					imageData.setState(ImageState.DOWNLOADING);
					imageData.setFederationMember(federationMember);					
					pendingImageDownloadMap.put(imageData.getName(), imageData);
					pendingImageDownloadDB.commit();
					imageStore.updateImage(imageData);
					imageStore.unlockImage(imageData.getName());

					downloadImage(imageData);
				}
			}
		}
	}

	protected long numberOfImagesToDownload() {
		
		//FIXME test
		String volumeDirPath = properties.getProperty("sebal_export_path");
		File volumePath = getExportDirPath(volumeDirPath);
		if (volumePath.exists() && volumePath.isDirectory()) {
			long availableVolumeSpace = volumePath.getFreeSpace();
			long numberOfImagesToDownload = availableVolumeSpace / DEFAULT_IMAGE_DIR_SIZE;
			//FIXME: ceil para o inteiro inferior
			return numberOfImagesToDownload;
		} else {
			throw new RuntimeException("VolumePath: " + volumeDirPath
					+ " is not a directory or does not exist");
		}
	}

	protected File getExportDirPath(String volumeDirPath) {
		return new File(volumeDirPath);
	}

	private void downloadImage(final ImageData imageData) {
		try {
			// FIXME: it blocks?
			NASARepository.downloadImage(imageData);
			

			// running Fmask
			int exitValue = new FMask().runFmask(imageData, properties);
			if (exitValue != 0) {
				LOGGER.error("It was not possible run Fmask for image "
						+ imageData.getName());
				imageData.setFederationMember(ImageDataStore.NONE);
			}			

			imageData.setState(ImageState.DOWNLOADED);
			imageStore.updateImage(imageData);
			pendingImageDownloadMap.remove(imageData.getName());
		} catch (Exception e) {
			LOGGER.error(
					"Couldn't download image " + imageData.getName() + ".", e);
			removeFromPendingAndUpdateState(imageData);
		}
	}
	
	private void removeFromPendingAndUpdateState(final ImageData imageData) {
		//FIXME: add log
		//FIXME: clean up garbage data from volumes
		try {
			imageData.setFederationMember(ImageDataStore.NONE);
			imageData.setState(ImageState.NOT_DOWNLOADED);
			imageStore.updateImage(imageData);
			pendingImageDownloadMap.remove(imageData.getName());
		} catch (SQLException e1) {
			Crawler.LOGGER.error("Error while updating image data: " + imageData.getName(), e1);
			System.out.println("Error while updating image data: " + imageData.getName() + "\n" + e1);
		}
	}

	private void deleteFetchedResultsFromVolume(Properties properties) throws IOException,
			InterruptedException, SQLException {
		
		List<ImageData> setOfImageData = imageStore.getAllImages();

		String exportPath = properties.getProperty("sebal_export_path");
				
		if (!exportPath.isEmpty() && exportPath != null) {
			for (ImageData imageData : setOfImageData) {
				if (imageData.getState().equals(ImageState.FINISHED)) {
					String imageDirPath = exportPath + "/results/"
							+ imageData.getName();
					File imageDir = new File(imageDirPath);

					if (!imageDir.exists() || !imageDir.isDirectory()) {
						LOGGER.debug("This file does not exist!");
						return;
					}
					FileUtils.deleteDirectory(imageDir);
					LOGGER.debug("Image " + imageData.getName() + " result files deleted successfully.");
				} else {
					continue;
				}
			}
		} else {
			// FIXME: Implement solution for this
			LOGGER.error("Volume directory path is null or empty!");
		}
	}

}