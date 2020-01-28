package org.fogbowcloud.saps.engine.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.log4j.Logger;
import org.fogbowcloud.saps.engine.core.dispatcher.notifier.Ward;
import org.fogbowcloud.saps.engine.core.model.SapsImage;
import org.fogbowcloud.saps.engine.core.model.SapsUser;
import org.fogbowcloud.saps.engine.core.model.enums.ImageTaskState;
import org.fogbowcloud.saps.engine.utils.SapsPropertiesConstants;

public class JDBCImageDataStore implements Catalog {

	private static final Logger LOGGER = Logger.getLogger(JDBCImageDataStore.class);

	private static final String IMAGE_TABLE_NAME = "TASKS";
	private static final String STATES_TABLE_NAME = "TIMESTAMPS";
	private static final String TASK_ID_COL = "task_id";
	private static final String DATASET_COL = "dataset";
	private static final String REGION_COL = "region";
	private static final String IMAGE_DATE_COL = "image_date";
	private static final String PRIORITY_COL = "priority";
	private static final String FEDERATION_MEMBER_COL = "federation_member";
	private static final String STATE_COL = "state";
	private static final String ARREBOL_JOB_ID = "arrebol_job_id";
	private static final String INPUTDOWNLOADING_TAG = "inputdownloading_tag";
	private static final String PREPROCESSING_TAG = "preprocessing_tag";
	private static final String PROCESSING_TAG = "processing_tag";
	private static final String INPUTDOWNLOADING_DIGEST = "inputdownloading_digest";
	private static final String PREPROCESSING_DIGEST = "preprocessing_digest";
	private static final String PROCESSING_DIGEST = "processing_digest";
	private static final String CREATION_TIME_COL = "creation_time";
	private static final String UPDATED_TIME_COL = "updated_time";
	private static final String IMAGE_STATUS_COL = "status";
	private static final String ERROR_MSG_COL = "error_msg";

	private static final String USERS_TABLE_NAME = "USERS";
	private static final String USER_EMAIL_COL = "user_email";
	private static final String USER_NAME_COL = "user_name";
	private static final String USER_PASSWORD_COL = "user_password";
	private static final String USER_STATE_COL = "active";
	private static final String USER_NOTIFY_COL = "user_notify";
	private static final String ADMIN_ROLE_COL = "admin_role";

	private static final String USERS_NOTIFY_TABLE_NAME = "NOTIFY";
	private static final String SUBMISSION_ID_COL = "submission_id";

	private static final String DEPLOY_CONFIG_TABLE_NAME = "DEPLOY_CONFIG";
	private static final String NFS_SERVER_IP_COL = "nfs_ip";
	private static final String NFS_SERVER_SSH_PORT_COL = "nfs_ssh_port";
	private static final String NFS_SERVER_PORT_COL = "nfs_port";

	private static final String PROVENANCE_TABLE_NAME = "PROVENANCE_DATA";
	private static final String INPUT_METADATA_COL = "input_metadata";
	private static final String INPUT_OPERATING_SYSTEM_COL = "input_operating_system";
	private static final String INPUT_KERNEL_VERSION_COL = "input_kernel_version";
	private static final String PREPROCESSING_METADATA_COL = "preprocessing_metadata";
	private static final String PREPROCESSING_OPERATING_SYSTEM_COL = "preprocessing_operating_system";
	private static final String PREPROCESSING_KERNEL_VERSION_COL = "preprocessing_kernel_version";
	private static final String OUTPUT_METADATA_COL = "output_metadata";
	private static final String OUTPUT_OPERATING_SYSTEM_COL = "output_operating_system";
	private static final String OUTPUT_KERNEL_VERSION_COL = "output_kernel_version";

	// Insert queries
	private static final String INSERT_FULL_IMAGE_TASK_SQL = "INSERT INTO " + IMAGE_TABLE_NAME
			+ " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

	private Map<String, Connection> lockedImages = new ConcurrentHashMap<>();
	private BasicDataSource connectionPool;

	public JDBCImageDataStore(Properties properties) throws SQLException {

		if (checkProperties(properties))
			if (properties == null) {
				throw new IllegalArgumentException("Properties arg must not be null.");
			}

		String imageStoreIP = properties.getProperty(DATASTORE_IP);
		String imageStorePort = properties.getProperty(DATASTORE_PORT);
		String imageStoreURLPrefix = properties.getProperty(DATASTORE_URL_PREFIX);
		String dbUserName = properties.getProperty(DATASTORE_USERNAME);
		String dbUserPass = properties.getProperty(DATASTORE_PASSWORD);
		String dbDrive = properties.getProperty(DATASTORE_DRIVER);
		String dbName = properties.getProperty(DATASTORE_NAME);

		LOGGER.info("Imagestore " + imageStoreIP + ":" + imageStorePort);
		init(imageStoreIP, imageStorePort, imageStoreURLPrefix, dbUserName, dbUserPass, dbDrive, dbName);
	}

	private boolean checkProperties(Properties properties) {
		if (properties == null) {
			LOGGER.error("Properties arg must not be null.");
			return false;
		}
		if (!properties.containsKey(DATASTORE_URL_PREFIX)) {
			LOGGER.error("Required property " + DATASTORE_URL_PREFIX + " was not set");
			return false;
		}
		if (!properties.containsKey(DATASTORE_USERNAME)) {
			LOGGER.error("Required property " + DATASTORE_USERNAME + " was not set");
			return false;
		}
		if (!properties.containsKey(DATASTORE_PASSWORD)) {
			LOGGER.error("Required property " + DATASTORE_PASSWORD + " was not set");
			return false;
		}
		if (!properties.containsKey(DATASTORE_DRIVER)) {
			LOGGER.error("Required property " + DATASTORE_DRIVER + " was not set");
			return false;
		}
		if (!properties.containsKey(DATASTORE_NAME)) {
			LOGGER.error("Required property " + DATASTORE_NAME + " was not set");
			return false;
		}
		LOGGER.debug("All properties for create JDBCImageDataStore are set");
		return true;
	}

	public JDBCImageDataStore(String imageStoreURLPrefix, String imageStoreIP, String imageStorePort, String dbUserName,
			String dbUserPass, String dbDrive, String dbName) throws SQLException {

		init(imageStoreIP, imageStorePort, imageStoreURLPrefix, dbUserName, dbUserPass, dbDrive, dbName);
	}

	private void init(String imageStoreIP, String imageStorePort, String imageStoreURLPrefix, String dbUserName,
			String dbUserPass, String dbDrive, String dbName) throws SQLException {
		connectionPool = createConnectionPool(imageStoreURLPrefix, imageStoreIP, imageStorePort, dbUserName, dbUserPass,
				dbDrive, dbName);
		createTable();
	}

	private void createTable() throws SQLException {

		Connection connection = null;
		Statement statement = null;

		try {
			connection = getConnection();
			statement = connection.createStatement();

			statement.execute("CREATE TABLE IF NOT EXISTS " + USERS_TABLE_NAME + "(" + USER_EMAIL_COL
					+ " VARCHAR(255) PRIMARY KEY, " + USER_NAME_COL + " VARCHAR(255), " + USER_PASSWORD_COL
					+ " VARCHAR(100), " + USER_STATE_COL + " BOOLEAN, " + USER_NOTIFY_COL + " BOOLEAN, "
					+ ADMIN_ROLE_COL + " BOOLEAN)");

			statement.execute("CREATE TABLE IF NOT EXISTS " + IMAGE_TABLE_NAME + "(" + TASK_ID_COL
					+ " VARCHAR(255) PRIMARY KEY, " + DATASET_COL + " VARCHAR(100), " + REGION_COL + " VARCHAR(100), "
					+ IMAGE_DATE_COL + " DATE, " + STATE_COL + " VARCHAR(100), " + ARREBOL_JOB_ID + " VARCHAR(100),"
					+ FEDERATION_MEMBER_COL + " VARCHAR(255), " + PRIORITY_COL + " INTEGER, " + USER_EMAIL_COL
					+ " VARCHAR(255) REFERENCES " + USERS_TABLE_NAME + "(" + USER_EMAIL_COL + "), "
					+ INPUTDOWNLOADING_TAG + " VARCHAR(100), " + INPUTDOWNLOADING_DIGEST + " VARCHAR(255), "
					+ PREPROCESSING_TAG + " VARCHAR(100), " + PREPROCESSING_DIGEST + " VARCHAR(255), " + PROCESSING_TAG
					+ " VARCHAR(100), " + PROCESSING_DIGEST + " VARCHAR(255), " + CREATION_TIME_COL + " TIMESTAMP, "
					+ UPDATED_TIME_COL + " TIMESTAMP, " + IMAGE_STATUS_COL + " VARCHAR(255), " + ERROR_MSG_COL
					+ " VARCHAR(255))");

			statement.execute("CREATE TABLE IF NOT EXISTS " + STATES_TABLE_NAME + "(" + TASK_ID_COL + " VARCHAR(255), "
					+ STATE_COL + " VARCHAR(100), " + UPDATED_TIME_COL + " TIMESTAMP" + ")");

			statement.execute("CREATE TABLE IF NOT EXISTS " + USERS_NOTIFY_TABLE_NAME + "(" + SUBMISSION_ID_COL
					+ " VARCHAR(255), " + TASK_ID_COL + " VARCHAR(255), " + USER_EMAIL_COL + " VARCHAR(255), "
					+ " PRIMARY KEY(" + SUBMISSION_ID_COL + ", " + TASK_ID_COL + ", " + USER_EMAIL_COL + "))");

			statement.execute("CREATE TABLE IF NOT EXISTS " + DEPLOY_CONFIG_TABLE_NAME + "(" + NFS_SERVER_IP_COL
					+ " VARCHAR(100), " + NFS_SERVER_SSH_PORT_COL + " VARCHAR(100), " + NFS_SERVER_PORT_COL
					+ " VARCHAR(100), " + FEDERATION_MEMBER_COL + " VARCHAR(255), " + " PRIMARY KEY("
					+ NFS_SERVER_IP_COL + ", " + NFS_SERVER_SSH_PORT_COL + ", " + NFS_SERVER_PORT_COL + ", "
					+ FEDERATION_MEMBER_COL + "))");

			statement.execute("CREATE TABLE IF NOT EXISTS " + PROVENANCE_TABLE_NAME + "(" + TASK_ID_COL
					+ " VARCHAR(255) PRIMARY KEY, " + INPUT_METADATA_COL + " VARCHAR(255), "
					+ INPUT_OPERATING_SYSTEM_COL + " VARCHAR(100), " + INPUT_KERNEL_VERSION_COL + " VARCHAR(100), "
					+ PREPROCESSING_METADATA_COL + " VARCHAR(255), " + PREPROCESSING_OPERATING_SYSTEM_COL
					+ " VARCHAR(100), " + PREPROCESSING_KERNEL_VERSION_COL + " VARCHAR(100), " + OUTPUT_METADATA_COL
					+ " VARCHAR(255), " + OUTPUT_OPERATING_SYSTEM_COL + " VARCHAR(100), " + OUTPUT_KERNEL_VERSION_COL
					+ " VARCHAR(100))");

			statement.close();
		} catch (SQLException e) {
			LOGGER.error("Error while initializing DataStore", e);
			throw e;
		} finally {
			close(statement, connection);
		}
	}

	private BasicDataSource createConnectionPool(String imageStoreURLPrefix, String imageStoreIP, String imageStorePort,
			String dbUserName, String dbUserPass, String dbDriver, String dbName) {

		String url = imageStoreURLPrefix + imageStoreIP + ":" + imageStorePort + "/" + dbName;

		LOGGER.debug("DatastoreURL: " + url);

		BasicDataSource pool = new BasicDataSource();
		pool.setUsername(dbUserName);
		pool.setPassword(dbUserPass);
		pool.setDriverClassName(dbDriver);

		pool.setUrl(url);
		pool.setInitialSize(1);

		return pool;
	}

	public Connection getConnection() throws SQLException {

		try {
			return connectionPool.getConnection();
		} catch (SQLException e) {
			LOGGER.error("Error while getting a new connection from the connection pool", e);
			throw e;
		}
	}

	protected void close(Statement statement, Connection conn) {
		close(statement);

		if (conn != null) {
			try {
				if (!conn.isClosed()) {
					conn.close();
				}
			} catch (SQLException e) {
				LOGGER.error("Couldn't close connection", e);
			}
		}
	}

	private void close(Statement statement) {
		if (statement != null) {
			try {
				if (!statement.isClosed()) {
					statement.close();
				}
			} catch (SQLException e) {
				LOGGER.error("Couldn't close statement", e);
			}
		}
	}

	private java.sql.Date javaDateToSqlDate(Date date) {
		return new java.sql.Date(date.getTime());
	}

	@Override
	public SapsImage addImageTask(String taskId, String dataset, String region, Date date, int priority, String user,
			String inputdownloadingPhaseTag, String digestInputdownloading, String preprocessingPhaseTag,
			String digestPreprocessing, String processingPhaseTag, String digestProcessing) throws SQLException {
		Timestamp now = new Timestamp(System.currentTimeMillis());
		SapsImage task = new SapsImage(taskId, dataset, region, date, ImageTaskState.CREATED,
				SapsImage.NONE_ARREBOL_JOB_ID, SapsImage.NONE_FEDERATION_MEMBER, priority, user,
				inputdownloadingPhaseTag, digestInputdownloading, preprocessingPhaseTag, digestPreprocessing,
				processingPhaseTag, digestProcessing, now, now, SapsImage.AVAILABLE, SapsImage.NON_EXISTENT_DATA);
		addImageTask(task);
		return task;
	}

	@Override
	public void addImageTask(SapsImage imageTask) throws SQLException {
		if (imageTask.getTaskId() == null || imageTask.getTaskId().isEmpty()) {
			LOGGER.error("Task with empty id.");
			throw new IllegalArgumentException("Task with empty id.");
		}
		if (imageTask.getDataset() == null || imageTask.getDataset().isEmpty()) {
			LOGGER.error("Task with empty dataset.");
			throw new IllegalArgumentException("Task with empty dataset.");
		}
		if (imageTask.getImageDate() == null) {
			LOGGER.error("Task must have a date.");
			throw new IllegalArgumentException("Task must have a date.");
		}
		if (imageTask.getUser() == null || imageTask.getUser().isEmpty()) {
			LOGGER.error("Task must have a user.");
			throw new IllegalArgumentException("Task must have a user.");
		}

		LOGGER.info("Adding image task " + imageTask.getTaskId() + " with priority " + imageTask.getPriority());
		LOGGER.info(imageTask.toString());

		PreparedStatement insertStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			insertStatement = connection.prepareStatement(INSERT_FULL_IMAGE_TASK_SQL);
			insertStatement.setString(1, imageTask.getTaskId());
			insertStatement.setString(2, imageTask.getDataset());
			insertStatement.setString(3, imageTask.getRegion());
			insertStatement.setDate(4, javaDateToSqlDate(imageTask.getImageDate()));
			insertStatement.setString(5, imageTask.getState().getValue());
			insertStatement.setString(6, imageTask.getArrebolJobId());
			insertStatement.setString(7, imageTask.getFederationMember());
			insertStatement.setInt(8, imageTask.getPriority());
			insertStatement.setString(9, imageTask.getUser());
			insertStatement.setString(10, imageTask.getInputdownloadingTag());
			insertStatement.setString(11, imageTask.getDigestInputdownloading());
			insertStatement.setString(12, imageTask.getPreprocessingTag());
			insertStatement.setString(13, imageTask.getDigestPreprocessing());
			insertStatement.setString(14, imageTask.getProcessingTag());
			insertStatement.setString(15, imageTask.getDigestProcessing());
			insertStatement.setTimestamp(16, imageTask.getCreationTime());
			insertStatement.setTimestamp(17, imageTask.getUpdateTime());
			insertStatement.setString(18, imageTask.getStatus());
			insertStatement.setString(19, imageTask.getError());
			insertStatement.setQueryTimeout(300);

			insertStatement.execute();
		} finally {
			close(insertStatement, connection);
		}
	}

	private static final String INSERT_USER_NOTIFICATION_SQL = "INSERT INTO " + USERS_NOTIFY_TABLE_NAME
			+ " VALUES(?, ?, ?)";

	@Override
	public void addUserNotification(String submissionId, String taskId, String userEmail) throws SQLException {
		LOGGER.info(
				"Adding image task " + taskId + " from submission " + submissionId + " notification for " + userEmail);
		if (taskId == null || taskId.isEmpty() || userEmail == null || userEmail.isEmpty()) {
			throw new IllegalArgumentException("Invalid task id " + taskId + " or user " + userEmail);
		}

		PreparedStatement insertStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			insertStatement = connection.prepareStatement(INSERT_USER_NOTIFICATION_SQL);
			insertStatement.setString(1, submissionId);
			insertStatement.setString(2, taskId);
			insertStatement.setString(3, userEmail);
			insertStatement.setQueryTimeout(300);

			insertStatement.execute();
		} finally {
			close(insertStatement, connection);
		}
	}

	private static final String INSERT_DEPLOY_CONFIG_SQL = "INSERT INTO " + DEPLOY_CONFIG_TABLE_NAME
			+ " VALUES(?, ?, ?, ?)";

	@Override
	public void addDeployConfig(String nfsIP, String nfsSshPort, String nfsPort, String federationMember)
			throws SQLException {
		LOGGER.info("Adding NFS IP " + nfsIP + " and port " + nfsPort + " from " + federationMember + " in DB");
		if (nfsIP == null || nfsIP.isEmpty() || nfsSshPort == null || nfsSshPort.isEmpty() || nfsPort == null
				|| nfsPort.isEmpty() || federationMember == null || federationMember.isEmpty()) {
			throw new IllegalArgumentException("Invalid NFS IP " + nfsIP + ", ssh port " + nfsSshPort + ", port "
					+ nfsPort + " or federation member " + federationMember);
		}

		PreparedStatement insertStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			insertStatement = connection.prepareStatement(INSERT_DEPLOY_CONFIG_SQL);
			insertStatement.setString(1, nfsIP);
			insertStatement.setString(2, nfsSshPort);
			insertStatement.setString(3, nfsPort);
			insertStatement.setString(4, federationMember);
			insertStatement.setQueryTimeout(300);

			insertStatement.execute();
		} finally {
			close(insertStatement, connection);
		}
	}

	private static final String UPDATE_DOWNLOADER_METADATA_INFO_SQL = "UPDATE " + PROVENANCE_TABLE_NAME + " SET "
			+ INPUT_METADATA_COL + " = ?, " + INPUT_OPERATING_SYSTEM_COL + " = ?, " + INPUT_KERNEL_VERSION_COL
			+ " = ? WHERE " + TASK_ID_COL + " = ?;";

	private static final String UPDATE_PREPROCESS_METADATA_INFO_SQL = "UPDATE " + PROVENANCE_TABLE_NAME + " SET "
			+ PREPROCESSING_METADATA_COL + " = ?, " + PREPROCESSING_OPERATING_SYSTEM_COL + " = ?, "
			+ PREPROCESSING_KERNEL_VERSION_COL + " = ? WHERE " + TASK_ID_COL + " = ?;";

	private static final String UPDATE_OUTPUT_METADATA_INFO_SQL = "UPDATE " + PROVENANCE_TABLE_NAME + " SET "
			+ OUTPUT_METADATA_COL + " = ?, " + OUTPUT_OPERATING_SYSTEM_COL + " = ?, " + OUTPUT_KERNEL_VERSION_COL
			+ " = ? WHERE " + TASK_ID_COL + " = ?;";

	@Override
	public void updateMetadataInfo(String metadataFilePath, String operatingSystem, String kernelVersion,
			String componentType, String taskId) throws SQLException {
		LOGGER.info("Updating metadata info for component " + componentType + " with taskId " + taskId
				+ "\nMetadataFilePath: " + metadataFilePath + " OperatingSystem: " + operatingSystem
				+ " KernelVersion: " + kernelVersion);
		if (metadataFilePath == null || metadataFilePath.isEmpty() || operatingSystem == null
				|| operatingSystem.isEmpty() || kernelVersion == null || kernelVersion.isEmpty()
				|| componentType == null || componentType.isEmpty() || taskId == null || taskId.isEmpty()) {
			throw new IllegalArgumentException("Invalid metadataFilePath " + metadataFilePath + ", operatingSystem "
					+ operatingSystem + ", kernelVersion " + kernelVersion + ", componentType " + componentType
					+ " or taskId " + taskId);
		}

		PreparedStatement updateStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			updateStatement = adjustMetadataStatementToComponent(componentType, updateStatement, connection);

			updateStatement.setString(1, metadataFilePath);
			updateStatement.setString(2, operatingSystem);
			updateStatement.setString(3, kernelVersion);
			updateStatement.setString(4, taskId);
			updateStatement.setQueryTimeout(300);

			updateStatement.execute();
		} finally {
			close(updateStatement, connection);
		}
	}

	protected PreparedStatement adjustMetadataStatementToComponent(String componentType,
			PreparedStatement insertStatement, Connection connection) throws SQLException {
		if (componentType.equals(SapsPropertiesConstants.INPUT_DOWNLOADER_COMPONENT_TYPE)) {
			insertStatement = connection.prepareStatement(UPDATE_DOWNLOADER_METADATA_INFO_SQL);
		} else if (componentType.equals(SapsPropertiesConstants.PREPROCESSOR_COMPONENT_TYPE)) {
			insertStatement = connection.prepareStatement(UPDATE_PREPROCESS_METADATA_INFO_SQL);
		} else if (componentType.equals(SapsPropertiesConstants.WORKER_COMPONENT_TYPE)) {
			insertStatement = connection.prepareStatement(UPDATE_OUTPUT_METADATA_INFO_SQL);
		}
		return insertStatement;
	}

	private static final String SELECT_ALL_USERS_TO_NOTIFY_SQL = "SELECT * FROM " + USERS_NOTIFY_TABLE_NAME;

	@Override
	public List<Ward> getUsersToNotify() throws SQLException {

		LOGGER.debug("Getting all users to notify");

		Statement statement = null;
		Connection conn = null;
		try {
			conn = getConnection();
			statement = conn.createStatement();
			statement.setQueryTimeout(300);

			statement.execute(SELECT_ALL_USERS_TO_NOTIFY_SQL);
			ResultSet rs = statement.getResultSet();
			List<Ward> wards = extractUsersToNotifyFrom(rs);
			return wards;
		} finally {
			close(statement, conn);
		}
	}

	private static final String SELECT_NFS_CONFIG_SQL = "SELECT " + NFS_SERVER_IP_COL + ", " + NFS_SERVER_SSH_PORT_COL
			+ " FROM " + DEPLOY_CONFIG_TABLE_NAME + " WHERE federation_member = ?";

	@Override
	public Map<String, String> getFederationNFSConfig(String federationMember) throws SQLException {

		LOGGER.debug("Getting NFS configuration for " + federationMember);

		PreparedStatement statement = null;
		Connection conn = null;
		try {
			conn = getConnection();
			statement = conn.prepareStatement(SELECT_NFS_CONFIG_SQL);
			statement.setString(1, federationMember);
			statement.setQueryTimeout(300);

			statement.execute();

			ResultSet rs = statement.getResultSet();
			HashMap<String, String> nfsConfig = extractNFSConfigFrom(rs);
			return nfsConfig;
		} finally {
			close(statement, conn);
		}
	}

	private List<Ward> extractUsersToNotifyFrom(ResultSet rs) throws SQLException {

		List<Ward> wards = new ArrayList<>();

		while (rs.next()) {
			wards.add(new Ward(rs.getString(SUBMISSION_ID_COL), rs.getString(TASK_ID_COL), ImageTaskState.ARCHIVED,
					rs.getString(USER_EMAIL_COL)));
		}

		return wards;
	}

	private HashMap<String, String> extractNFSConfigFrom(ResultSet rs) throws SQLException {

		HashMap<String, String> nfsConfig = new HashMap<String, String>();

		while (rs.next()) {
			nfsConfig.put(rs.getString(NFS_SERVER_IP_COL), rs.getString(NFS_SERVER_PORT_COL));
		}

		return nfsConfig;
	}

	private static final String SELECT_USER_NOTIFIABLE_SQL = "SELECT " + USER_NOTIFY_COL + " FROM " + USERS_TABLE_NAME
			+ " WHERE " + USER_EMAIL_COL + " = ?";

	@Override
	public boolean isUserNotifiable(String userEmail) throws SQLException {
		LOGGER.debug("Verifying if user is notifiable");

		PreparedStatement statement = null;
		Connection conn = null;
		try {
			conn = getConnection();
			statement = conn.prepareStatement(SELECT_USER_NOTIFIABLE_SQL);
			statement.setString(1, userEmail);
			statement.setQueryTimeout(300);

			statement.execute();

			ResultSet rs = statement.getResultSet();
			rs.next();
			return rs.getBoolean(1);
		} finally {
			close(statement, conn);
		}
	}

	private static final String REMOVE_USER_NOTIFY_SQL = "DELETE FROM " + USERS_NOTIFY_TABLE_NAME + " WHERE "
			+ SUBMISSION_ID_COL + " = ? AND " + TASK_ID_COL + " = ? AND " + USER_EMAIL_COL + " = ?";

	@Override
	public void removeNotification(String submissionId, String taskId, String userEmail) throws SQLException {
		LOGGER.debug("Removing task " + taskId + " notification for " + userEmail);
		if (submissionId == null || submissionId.isEmpty() || taskId == null || taskId.isEmpty() || userEmail == null
				|| userEmail.isEmpty()) {
			throw new IllegalArgumentException(
					"Invalid submissionId " + submissionId + ", taskId " + taskId + " or user " + userEmail);
		}

		PreparedStatement insertStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			insertStatement = connection.prepareStatement(REMOVE_USER_NOTIFY_SQL);
			insertStatement.setString(1, submissionId);
			insertStatement.setString(2, taskId);
			insertStatement.setString(3, userEmail);
			insertStatement.setQueryTimeout(300);

			insertStatement.execute();
		} finally {
			close(insertStatement, connection);
		}
	}

	private static final String INSERT_NEW_STATE_TIMESTAMP_SQL = "INSERT INTO " + STATES_TABLE_NAME
			+ " VALUES(?, ?, now())";

	@Override
	public void addStateStamp(String taskId, ImageTaskState state, Timestamp timestamp) throws SQLException {
		if (taskId == null || taskId.isEmpty() || state == null) {
			LOGGER.error("Task id or state was null.");
			throw new IllegalArgumentException("Task id or state was null.");
		}
		LOGGER.info("Adding task " + taskId + " state " + state.getValue() + " with timestamp " + timestamp
				+ " into Catalogue");

		PreparedStatement insertStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			insertStatement = connection.prepareStatement(INSERT_NEW_STATE_TIMESTAMP_SQL);
			insertStatement.setString(1, taskId);
			insertStatement.setString(2, state.getValue());
			insertStatement.setQueryTimeout(300);

			insertStatement.execute();
		} finally {
			close(insertStatement, connection);
		}
	}

	private static final String INSERT_NEW_USER_SQL = "INSERT INTO " + USERS_TABLE_NAME + " VALUES(?, ?, ?, ?, ?, ?)";

	@Override
	public void addUser(String userEmail, String userName, String userPass, boolean userState, boolean userNotify,
			boolean adminRole) throws SQLException {

		LOGGER.info("Adding user " + userName + " into DB");
		if (userName == null || userName.isEmpty() || userPass == null || userPass.isEmpty()) {
			throw new IllegalArgumentException("Unable to create user with empty name or password.");
		}

		PreparedStatement insertStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			insertStatement = connection.prepareStatement(INSERT_NEW_USER_SQL);
			insertStatement.setString(1, userEmail);
			insertStatement.setString(2, userName);
			insertStatement.setString(3, userPass);
			insertStatement.setBoolean(4, userState);
			insertStatement.setBoolean(5, userNotify);
			insertStatement.setBoolean(6, adminRole);
			insertStatement.setQueryTimeout(300);

			insertStatement.execute();
		} finally {
			close(insertStatement, connection);
		}
	}

	private static String UPDATE_USER_STATE_SQL = "UPDATE " + USERS_TABLE_NAME + " SET " + USER_STATE_COL
			+ " = ? WHERE " + USER_EMAIL_COL + " = ?";

	@Override
	public void updateUserState(String userEmail, boolean userState) throws SQLException {

		LOGGER.info("Updating user " + userEmail + " state to " + userState);
		if (userEmail == null || userEmail.isEmpty()) {
			throw new IllegalArgumentException("Invalid user " + userEmail);
		}

		PreparedStatement updateStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			updateStatement = connection.prepareStatement(UPDATE_USER_STATE_SQL);
			updateStatement.setBoolean(1, userState);
			updateStatement.setString(2, userEmail);
			updateStatement.setQueryTimeout(300);

			updateStatement.execute();
		} finally {
			close(updateStatement, connection);
		}
	}

	private static final String UPDATE_IMAGEDATA_SQL = "UPDATE " + IMAGE_TABLE_NAME + " SET " + STATE_COL + " = ?, "
			+ UPDATED_TIME_COL + " = now(), " + IMAGE_STATUS_COL + " = ?, " + ERROR_MSG_COL + " = ?, " + ARREBOL_JOB_ID
			+ " = ? " + "WHERE " + TASK_ID_COL + " = ?";

	@Override
	public void updateImageTask(SapsImage imagetask) throws SQLException {
		if (imagetask == null) {
			LOGGER.error("Trying to update null image task.");
			throw new IllegalArgumentException("Trying to update null image task.");
		}

		PreparedStatement updateStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			updateStatement = connection.prepareStatement(UPDATE_IMAGEDATA_SQL);
			updateStatement.setString(1, imagetask.getState().getValue());
			updateStatement.setString(2, imagetask.getStatus());
			updateStatement.setString(3, imagetask.getError());
			updateStatement.setString(4, imagetask.getArrebolJobId());
			updateStatement.setString(5, imagetask.getTaskId());
			updateStatement.setQueryTimeout(300);

			updateStatement.execute();
		} finally {
			close(updateStatement, connection);
		}
	}
	
	private static final String SELECT_ALL_IMAGES_SQL = "SELECT * FROM " + IMAGE_TABLE_NAME;

	@Override
	public List<SapsImage> getAllTasks() throws SQLException {
		Statement statement = null;
		Connection conn = null;
		try {
			conn = getConnection();
			statement = conn.createStatement();
			statement.setQueryTimeout(300);

			statement.execute(SELECT_ALL_IMAGES_SQL);
			ResultSet rs = statement.getResultSet();
			return extractImageTaskFrom(rs);
		} finally {
			close(statement, conn);
		}
	}

	private static final String SELECT_IMAGES_IN_PROCESSING_TO_ARREBOL_SQL = "SELECT * FROM " + IMAGE_TABLE_NAME
			+ " WHERE " + STATE_COL + " = '" + ImageTaskState.DOWNLOADING.getValue() + "' OR " + STATE_COL + " = '"
			+ ImageTaskState.PREPROCESSING.getValue() + "' OR " + STATE_COL + " = '" + ImageTaskState.RUNNING.getValue()
			+ "'";

	/**
	 * get tasks in processing to Arrebol
	 */
	public List<SapsImage> getTasksInProcessingState() throws SQLException {
		Statement statement = null;
		Connection conn = null;
		try {
			conn = getConnection();
			statement = conn.createStatement();
			statement.setQueryTimeout(300);

			statement.execute(SELECT_IMAGES_IN_PROCESSING_TO_ARREBOL_SQL);
			ResultSet rs = statement.getResultSet();
			return extractImageTaskFrom(rs);
		} finally {
			close(statement, conn);
		}
	}

	private static final String SELECT_USER_SQL = "SELECT * FROM " + USERS_TABLE_NAME + " WHERE " + USER_EMAIL_COL
			+ " = ?";

	@Override
	public SapsUser getUser(String userEmail) throws SQLException {

		if (userEmail == null || userEmail.isEmpty()) {
			LOGGER.error("Invalid userEmail " + userEmail);
			return null;
		}
		PreparedStatement selectStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			selectStatement = connection.prepareStatement(SELECT_USER_SQL);
			selectStatement.setString(1, userEmail);
			selectStatement.setQueryTimeout(300);

			selectStatement.execute();

			ResultSet rs = selectStatement.getResultSet();
			if (rs.next()) {
				SapsUser sebalUser = extractSapsUserFrom(rs);
				return sebalUser;
			}
			rs.close();
			return null;
		} finally {
			close(selectStatement, connection);
		}
	}

	private static final String SELECT_IMAGES_IN_STATE_SQL = "SELECT * FROM " + IMAGE_TABLE_NAME + " WHERE " + STATE_COL
			+ " = ? " + "ORDER BY " + PRIORITY_COL + " ASC";

	private static final String SELECT_LIMITED_IMAGES_IN_STATE_SQL = "SELECT * FROM " + IMAGE_TABLE_NAME + " WHERE "
			+ STATE_COL + " = ? ORDER BY " + PRIORITY_COL + " ASC LIMIT ?";

	@Override
	public List<SapsImage> getIn(ImageTaskState state, int limit) throws SQLException {
		if (state == null) {
			LOGGER.error("A state must be given");
			throw new IllegalArgumentException("Can't recover tasks. State was null.");
		}
		PreparedStatement selectStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			if (limit == UNLIMITED) {
				selectStatement = connection.prepareStatement(SELECT_IMAGES_IN_STATE_SQL);
				selectStatement.setString(1, state.getValue());
				selectStatement.setQueryTimeout(300);

				selectStatement.execute();
			} else {
				selectStatement = connection.prepareStatement(SELECT_LIMITED_IMAGES_IN_STATE_SQL);
				selectStatement.setString(1, state.getValue());
				selectStatement.setInt(2, limit);
				selectStatement.setQueryTimeout(300);

				selectStatement.execute();
			}

			ResultSet rs = selectStatement.getResultSet();
			List<SapsImage> imageDatas = extractImageTaskFrom(rs);
			rs.close();
			return imageDatas;
		} finally {
			close(selectStatement, connection);
		}
	}

	private static SapsUser extractSapsUserFrom(ResultSet rs) throws SQLException {
		SapsUser sebalUser = new SapsUser(rs.getString(USER_EMAIL_COL), rs.getString(USER_NAME_COL),
				rs.getString(USER_PASSWORD_COL), rs.getBoolean(USER_STATE_COL), rs.getBoolean(USER_NOTIFY_COL),
				rs.getBoolean(ADMIN_ROLE_COL));

		return sebalUser;
	}

	private static List<SapsImage> extractImageTaskFrom(ResultSet rs) throws SQLException {
		List<SapsImage> imageTasks = new ArrayList<>();
		while (rs.next()) {
			imageTasks.add(new SapsImage(rs.getString(TASK_ID_COL), rs.getString(DATASET_COL), rs.getString(REGION_COL),
					rs.getDate(IMAGE_DATE_COL), ImageTaskState.getStateFromStr(rs.getString(STATE_COL)),
					rs.getString(ARREBOL_JOB_ID), rs.getString(FEDERATION_MEMBER_COL), rs.getInt(PRIORITY_COL),
					rs.getString(USER_EMAIL_COL), rs.getString(INPUTDOWNLOADING_TAG),
					rs.getString(INPUTDOWNLOADING_DIGEST), rs.getString(PREPROCESSING_TAG),
					rs.getString(PREPROCESSING_DIGEST), rs.getString(PROCESSING_TAG), rs.getString(PROCESSING_DIGEST),
					rs.getTimestamp(CREATION_TIME_COL), rs.getTimestamp(UPDATED_TIME_COL),
					rs.getString(IMAGE_STATUS_COL), rs.getString(ERROR_MSG_COL)));
		}
		return imageTasks;
	}

	private static final String SELECT_TASK_SQL = "SELECT * FROM " + IMAGE_TABLE_NAME + " WHERE " + TASK_ID_COL
			+ " = ?";

	@Override
	public SapsImage getTask(String taskId) throws SQLException {
		if (taskId == null) {
			LOGGER.error("Invalid image task " + taskId);
			throw new IllegalArgumentException("Invalid image task " + taskId);
		}
		PreparedStatement selectStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			selectStatement = connection.prepareStatement(SELECT_TASK_SQL);
			selectStatement.setString(1, taskId);
			selectStatement.setQueryTimeout(300);

			selectStatement.execute();

			ResultSet rs = selectStatement.getResultSet();
			List<SapsImage> imageDatas = extractImageTaskFrom(rs);
			rs.close();
			return imageDatas.get(0);
		} finally {
			close(selectStatement, connection);
		}
	}

	private static final String REMOVE_STATE_SQL = "DELETE FROM " + STATES_TABLE_NAME + " WHERE " + TASK_ID_COL
			+ " = ? AND " + STATE_COL + " = ? AND " + UPDATED_TIME_COL + " = ?";

	@Override
	public void removeStateStamp(String taskId, ImageTaskState state, Timestamp timestamp) throws SQLException {
		LOGGER.info("Removing task " + taskId + " state " + state.getValue() + " with timestamp " + timestamp);
		if (taskId == null || taskId.isEmpty() || state == null) {
			LOGGER.error("Invalid task " + taskId + " or state " + state.getValue());
			throw new IllegalArgumentException("Invalid task " + taskId);
		}

		PreparedStatement removeStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			removeStatement = connection.prepareStatement(REMOVE_STATE_SQL);
			removeStatement.setString(1, taskId);
			removeStatement.setString(2, state.getValue());
			removeStatement.setTimestamp(3, timestamp);
			removeStatement.setQueryTimeout(300);

			removeStatement.execute();
		} finally {
			close(removeStatement, connection);
		}
	}

	private final String PROCESSED_IMAGES_QUERY = "SELECT * FROM " + IMAGE_TABLE_NAME + " WHERE " + STATE_COL
			+ " = ? AND " + REGION_COL + " = ? AND " + IMAGE_DATE_COL + " BETWEEN ? AND ? AND " + PREPROCESSING_TAG
			+ " = ? AND " + INPUTDOWNLOADING_TAG + " = ? AND " + PROCESSING_TAG + " = ?";

	@Override
	public List<SapsImage> getProcessedImages(String region, Date initDate, Date endDate, String inputGathering,
			String inputPreprocessing, String algorithmExecution) throws SQLException {
		PreparedStatement queryStatement = null;
		Connection connection = null;

		try {
			connection = getConnection();

			queryStatement = connection.prepareStatement(PROCESSED_IMAGES_QUERY);
			queryStatement.setString(1, ImageTaskState.ARCHIVED.getValue());
			queryStatement.setString(2, region);
			queryStatement.setDate(3, javaDateToSqlDate(initDate));
			queryStatement.setDate(4, javaDateToSqlDate(endDate));
			queryStatement.setString(5, inputPreprocessing);
			queryStatement.setString(6, inputGathering);
			queryStatement.setString(7, algorithmExecution);
			queryStatement.setQueryTimeout(300);

			ResultSet result = queryStatement.executeQuery();
			return extractImageTaskFrom(result);
		} finally {
			close(queryStatement, connection);
		}
	}

}
