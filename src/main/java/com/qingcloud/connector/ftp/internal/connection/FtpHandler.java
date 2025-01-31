package com.qingcloud.connector.ftp.internal.connection;

import com.qingcloud.connector.ftp.FtpConnectionOptions;
import com.qingcloud.connector.ftp.internal.constants.EFtpMode;
import com.qingcloud.connector.ftp.internal.constants.ReadMode;
import com.qingcloud.connector.ftp.internal.options.FtpReadOptions;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.qingcloud.connector.ftp.internal.constants.ConstantValue.*;

/**
 * handler for ftp.
 */
public class FtpHandler implements IFtpHandler, Serializable {
	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(FtpHandler.class);

	private FTPClient ftpClient = null;

	private static final String SP = File.separator;

	public FTPClient getFtpClient() {
		return ftpClient;
	}

	private boolean withSize = false;

	@Override
	public void loginFtpServer(FtpConnectionOptions options) {
		ftpClient = new FTPClient();
		try {
			ftpClient.connect(options.getHost(), options.getPort());
			ftpClient.login(
				Objects.requireNonNull(options.getUsername()).orElse("ftp"),
				Objects.requireNonNull(options.getPassword()).orElse(""));
			ftpClient.setConnectTimeout(options.getTimeout());
			ftpClient.setDataTimeout(options.getTimeout());
			ftpClient.setSoTimeout(options.getTimeout());
			if (EFtpMode.PASV.name().equalsIgnoreCase(options.getConnectPattern())) {
				ftpClient.enterRemotePassiveMode();
				ftpClient.enterLocalPassiveMode();
			} else if (EFtpMode.PORT.name().equalsIgnoreCase(options.getConnectPattern())) {
				ftpClient.enterLocalActiveMode();
			}
			int reply = ftpClient.getReplyCode();
			if (!FTPReply.isPositiveCompletion(reply)) {
				ftpClient.disconnect();
				String message = String.format(
						"与ftp服务器建立连接失败,请检查用户名和密码是否正确: [%s]",
						"message:host =" + options.getHost() + ",username = " + options.getUsername()
								+ ",port =" + options.getPort());
				LOG.error(message);
				throw new RuntimeException(message);
			}
			String fileEncoding = System.getProperty(SYSTEM_PROPERTIES_KEY_FILE_ENCODING);
			ftpClient.setControlEncoding(fileEncoding);
			ftpClient.setListHiddenFiles(true);
			if (options instanceof FtpReadOptions && !((FtpReadOptions) options)
					.getReadMode()
					.equalsIgnoreCase(ReadMode.ONCE.name())) {
				withSize = true;
			}
		} catch (Exception e) {
			throw new RuntimeException("ftp连接失败, host = "+options.getHost()+":"+options.getPort(), e);
		}
	}

	@Override
	public void logoutFtpServer() throws IOException {
		if (ftpClient.isConnected()) {
			try {
				ftpClient.logout();
			} finally {
				if (ftpClient.isConnected()) {
					ftpClient.disconnect();
				}
				withSize = false;
			}
		}
	}

	@Override
	public boolean isDirExist(String directoryPath) {
		String originDir = null;
		try {
			originDir = ftpClient.printWorkingDirectory();
			ftpClient.enterLocalPassiveMode();
			FTPFile[] ftpFiles = ftpClient.listFiles(new String(directoryPath.getBytes(
					StandardCharsets.UTF_8), FTP.DEFAULT_CONTROL_ENCODING));
			if (ftpFiles.length == 0) {
				throw new FileNotFoundException(
						"file or path is not exist, please check the path or the permissions of account, path = "
								+ directoryPath);
			} else {
				return FTPReply.isPositiveCompletion(ftpClient.cwd(directoryPath));
			}
		} catch (IOException e) {
			String message = String.format("进入目录：[%s]时发生I/O异常", directoryPath);
			LOG.error(message);
			throw new RuntimeException(message, e);
		} finally {
			if (originDir != null) {
				try {
					ftpClient.changeWorkingDirectory(originDir);
				} catch (IOException e) {
					LOG.error(e.getMessage());
				}
			}
		}
	}

	@Override
	public boolean isFileExist(String filePath) {
		return !isDirExist(filePath);
	}

	@Override
	public List<String> getFiles(String path) {
		List<String> sources = new ArrayList<>();
		ftpClient.enterLocalPassiveMode();
		if (path.endsWith(File.separator) && isDirExist(path)) {
		} else if (isFileExist(path)) {
			sources.add(path);
			return sources;
		} else {
			path = path + SP;
		}
		try {
			FTPFile[] ftpFiles = ftpClient.listFiles(new String(
					path.getBytes(StandardCharsets.UTF_8),
					FTP.DEFAULT_CONTROL_ENCODING));
			if (ftpFiles != null) {
				List<FTPFile> ftpLists = Arrays
						.stream(ftpFiles)
						.sorted(Comparator.comparing(FTPFile::getTimestamp))
						.collect(Collectors.toList());
				for (FTPFile ftpFile : ftpLists) {
					// .和..是特殊文件
					if (StringUtils.endsWith(ftpFile.getName(), POINT_SYMBOL)
							|| StringUtils.endsWith(
							ftpFile.getName(),
							TWO_POINT_SYMBOL)) {
						continue;
					}
					sources.addAll(getFiles(path + ftpFile.getName(), ftpFile));
				}
			}
		} catch (IOException e) {
			LOG.error("", e);
			throw new RuntimeException(e);
		}
		return sources;
	}

	/**
	 * 递归获取指定路径下的所有文件(暂无过滤).
	 *
	 * @param path
	 * @param file
	 *
	 * @return
	 *
	 * @throws IOException
	 */
	private List<String> getFiles(String path, FTPFile file) throws IOException {
		List<String> sources = new ArrayList<>();
		if (file.isDirectory()) {
			if (!path.endsWith(SP)) {
				path = path + SP;
			}
			ftpClient.enterLocalPassiveMode();
			FTPFile[] ftpFiles = ftpClient.listFiles(new String(
					path.getBytes(StandardCharsets.UTF_8),
					FTP.DEFAULT_CONTROL_ENCODING));
			if (ftpFiles != null) {
				for (FTPFile ftpFile : ftpFiles) {
					if (StringUtils.endsWith(ftpFile.getName(), POINT_SYMBOL)
							|| StringUtils.endsWith(
							ftpFile.getName(),
							TWO_POINT_SYMBOL)) {
						continue;
					}
					sources.addAll(getFiles(path + ftpFile.getName(), ftpFile));
				}
			}
		} else {
			if (withSize) {
				path = path + FILE_REGEX_SPLIT + file.getSize();
			}
			sources.add(path);
		}
		return sources;
	}

	@Override
	public void mkDirRecursive(String directoryPath) {
		StringBuilder dirPath = new StringBuilder();
		dirPath.append(IOUtils.DIR_SEPARATOR_UNIX);
		String[] dirSplit = StringUtils.split(directoryPath, IOUtils.DIR_SEPARATOR_UNIX);
		String message = String.format("创建目录:%s时发生异常,请确认与ftp服务器的连接正常,拥有目录创建权限", directoryPath);
		try {
			// ftp server不支持递归创建目录,只能一级一级创建
			for (String dirName : dirSplit) {
				dirPath.append(dirName);
				boolean mkdirSuccess = mkDirSingleHierarchy(dirPath.toString());
				dirPath.append(IOUtils.DIR_SEPARATOR_UNIX);
				if (!mkdirSuccess) {
					throw new RuntimeException(message);
				}
			}
		} catch (IOException e) {
			message = String.format("%s, errorMessage:%s", message,
					e.getMessage());
			LOG.error(message);
			throw new RuntimeException(message, e);
		}
	}

	private boolean mkDirSingleHierarchy(String directoryPath) throws IOException {
		boolean isDirExist = this.ftpClient
				.changeWorkingDirectory(directoryPath);
		// 如果directoryPath目录不存在,则创建
		if (!isDirExist) {
			int replayCode = this.ftpClient.mkd(directoryPath);
			if (replayCode != FTPReply.COMMAND_OK
					&& replayCode != FTPReply.PATHNAME_CREATED) {
				return false;
			}
		}
		return true;
	}

	@Override
	public OutputStream getOutputStream(String filePath) {
		try {
			this.printWorkingDirectory();
			String parentDir = filePath.substring(
					0,
					StringUtils.lastIndexOf(filePath, IOUtils.DIR_SEPARATOR_UNIX));
			this.ftpClient.changeWorkingDirectory(parentDir);
			this.printWorkingDirectory();
			OutputStream writeOutputStream = this.ftpClient
					.appendFileStream(filePath);
			if (null == writeOutputStream) {
				String message = String.format(
						"打开FTP文件[%s]获取写出流时出错,请确认文件%s有权限创建，有权限写出等", filePath,
						filePath);
				throw new RuntimeException(message);
			}

			return writeOutputStream;
		} catch (IOException e) {
			String message = String.format(
					"写出文件 : [%s] 时出错,请确认文件:[%s]存在且配置的用户有权限写, errorMessage:%s",
					filePath, filePath, e.getMessage());
			LOG.error(message);
			throw new RuntimeException(message, e);
		}
	}

	private void printWorkingDirectory() {
		try {
			LOG.info(String.format(
					"current working directory:%s",
					this.ftpClient.printWorkingDirectory()));
		} catch (Exception e) {
			LOG.warn(String.format(
					"printWorkingDirectory error:%s",
					e.getMessage()));
		}
	}

	@Override
	public void deleteAllFilesInDir(String dir, List<String> exclude) {
		if (isDirExist(dir)) {
			if (!dir.endsWith(SP)) {
				dir = dir + SP;
			}

			try {
				FTPFile[] ftpFiles = ftpClient.listFiles(new String(
						dir.getBytes(StandardCharsets.UTF_8),
						FTP.DEFAULT_CONTROL_ENCODING));
				if (ftpFiles != null) {
					for (FTPFile ftpFile : ftpFiles) {
						if (CollectionUtils.isNotEmpty(exclude)
								&& exclude.contains(ftpFile.getName())) {
							continue;
						}
						if (StringUtils.endsWith(ftpFile.getName(), POINT_SYMBOL)
								|| StringUtils.endsWith(
								ftpFile.getName(),
								TWO_POINT_SYMBOL)) {
							continue;
						}
						deleteAllFilesInDir(dir + ftpFile.getName(), exclude);
					}
				}

				if (CollectionUtils.isEmpty(exclude)) {
					ftpClient.rmd(dir);
				}
			} catch (IOException e) {
				LOG.error("", e);
				throw new RuntimeException(e);
			}
		} else if (isFileExist(dir)) {
			try {
				ftpClient.deleteFile(dir);
			} catch (IOException e) {
				LOG.error("", e);
				throw new RuntimeException(e);
			}
		}
	}

	@Override
	public InputStream getInputStream(String filePath) {
		try {
			ftpClient.enterLocalPassiveMode();
			InputStream is = ftpClient.retrieveFileStream(new String(filePath.getBytes(
					StandardCharsets.UTF_8), FTP.DEFAULT_CONTROL_ENCODING));
			return is;
		} catch (IOException e) {
			String message = String.format(
					"读取文件 : [%s] 时出错,请确认文件：[%s]存在且配置的用户有权限读取",
					filePath,
					filePath);
			LOG.error(message);
			throw new RuntimeException(message, e);
		}
	}

	@Override
	public List<String> listDirs(String path) {
		List<String> sources = new ArrayList<>();
		if (isDirExist(path)) {
			if (!path.endsWith(SP)) {
				path = path + SP;
			}

			try {
				FTPFile[] ftpFiles = ftpClient.listFiles(new String(
						path.getBytes(StandardCharsets.UTF_8),
						FTP.DEFAULT_CONTROL_ENCODING));
				if (ftpFiles != null) {
					for (FTPFile ftpFile : ftpFiles) {
						if (StringUtils.endsWith(ftpFile.getName(), POINT_SYMBOL)
								|| StringUtils.endsWith(
								ftpFile.getName(),
								TWO_POINT_SYMBOL)) {
							continue;
						}
						sources.add(path + ftpFile.getName());
					}
				}
			} catch (IOException e) {
				LOG.error("", e);
				throw new RuntimeException(e);
			}
		}

		return sources;
	}

	@Override
	public void rename(String oldPath, String newPath) throws IOException {
		ftpClient.rename(oldPath, newPath);
	}

	@Override
	public void completePendingCommand() throws IOException {
		try {
			// throw exception when return false
			if (!ftpClient.completePendingCommand()) {
				throw new IOException("I/O error occurs while sending or receiving data");
			}
		} catch (IOException e) {
			LOG.error("I/O error occurs while sending or receiving data");
			throw new IOException(e);
		}
	}
}
