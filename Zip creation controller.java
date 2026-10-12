package com.sbi.zip.controller;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import com.sbi.constants.FilePaths;
import com.sbi.zip.DAO.PerfiosDetailsDAO;
import com.sbi.zip.configuration.DMSProperties;
import com.sbi.zip.dto.Customer;
import com.sbi.zip.dto.CustomerCsvRecord;
import com.sbi.zip.service.PerfiosService;
import com.sbi.zip.utils.FolderIdentifier;

@Controller
public class ZipCreationController {

	@Autowired
	PerfiosService perfiosService;

	@Autowired 
	PerfiosDetailsDAO perfiosDetailsDAO;

	private static final Logger logger = (Logger) LogManager.getLogger(ZipCreationController.class);
	static String docDate = new SimpleDateFormat("dd.MM.yyyy_HH.mm").format(new Date());

	public static String docFilePath = DMSProperties.getInstance().getProperty("DOC_FILE_PATH") + "/"
			+ "FI_UTIL5_SUCCESS_DOC_FILE_" + DMSProperties.getInstance().getProperty("UTIL_NUM") + "_1_" + docDate
			+ ".txt"; // GBSS_REPORTS_DOC_FILE_1_21.11.2020_14.17.txt
	public static String docFilePathExp = DMSProperties.getInstance().getProperty("DOC_FILE_PATH") + "/"
			+ "FI_UTIL5_EXCEPTION_DOC_FILE_" + DMSProperties.getInstance().getProperty("UTIL_NUM") + "_1_" + docDate
			+ ".txt"; // GBSS_REPORTS_DOC_FILE_1_21.11.2020_14.17.txt
	public static String docFilePathResponse = DMSProperties.getInstance().getProperty("RES_DOC_FILE_PATH");

	public static int batchSize=Integer.parseInt(DMSProperties.getInstance().getProperty("BATCH_SIZE"));

	public static String outputPath = DMSProperties.getInstance().getProperty("OUTPUT_FOLD");
	public static String cleanPath = DMSProperties.getInstance().getProperty("CLEAN_FOLD");
	public static String zipOutputDirPath=DMSProperties.getInstance().getProperty("Zip.Output");
	public static String cbsOutputDirectoryPath=DMSProperties.getInstance().getProperty("Cbs.Data");


	long staleProcessingMinutes=1;
	long stabilityWindowSeconds=60;

	//private int clusterSize=1;
//	private int serverIndex=0;
	String lcpcLists=DMSProperties.getInstance().getProperty("LCPC_NAME");

	String inputPath = FilePaths.inputFolder;
	String backupPath = FilePaths.BackupFolder;
	String Exception = FilePaths.ExceptionFolder;
	String cleanFolder = FilePaths.cleanFolder;
	String exceptionFolder = FilePaths.ExceptionFolder;
	String processigPath=FilePaths.processingPath;

	private final String hostId = resolveHostId();

	private static String resolveHostId() {
		try {
			return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
		} catch (Exception e) {
			return "unknown-" + ProcessHandle.current().pid();
		}
	}

	public void processLcpc() throws IOException {
		Set<String> lcpcSet=Arrays.stream(lcpcLists.split(",")).collect(Collectors.toSet());
		for(String lcpc: lcpcSet) {
			String inputPathLcpc=inputPath+"/"+lcpc;
			String inputProcessPathLcpc=processigPath+"/"+lcpc;
		//	zipCreate(inputPathLcpc,inputProcessPathLcpc,lcpc);
			processAvailableCustomer(inputPathLcpc,inputProcessPathLcpc,lcpc);
		}	
	}
	
	
	public void processAvailableCustomer(String inputPathLcpc, String inputProcessPathLcpc, String lcpc){
		logger.info("Inside processAvailableCustomer");
		while(true) {
		//	String newLotNumber = perfiosService.fetchNewLotNumber(lcpc);
		//	logger.info("newLotNumber " + newLotNumber);

			List<Path> customerFolders=perfiosService.fetchCustomersOnLot(Paths.get(inputPathLcpc),lcpc);
			if (customerFolders.size()==0) {
				logger.info("No records found for the LCPC  "+lcpc);
				
				break;
			}
			try{
				zipCreate(inputPathLcpc,inputProcessPathLcpc,lcpc,customerFolders);
				logger.info("zipCreated for a lot in the folder   "+lcpc);
			}catch(Exception e) {
				logger.error(" Error from zipCreate    "+e.getMessage());
				break;
			}
			logger.info("No of customer folders found " + customerFolders.size());
		}
	}
	

	public void zipCreate(String inputPathLcpc, String inputProcessPathLcpc, String lcpc, List<Path> customerFolders) throws IOException {
		logger.info("__________________________________________________"+lcpc);
		logger.info("Inside MAIN of Utility....create doc file..............");

		File ExceptionFold = new File(Exception);
		logger.info("The input folder path is :"  + inputPath);
		logger.info("The Backup folder path is :"  + backupPath);
		logger.info("The Exception folder path is :"  + Exception);

		File CleanFolder = new File(cleanFolder);
		String currentDate=parsedCurrentdate();

	//	Path inputProcessingDirectory=Paths.get(inputPathLcpc);
		Path processingDir = Paths.get(inputProcessPathLcpc);

		String newLotNumber = perfiosService.fetchNewLotNumber(lcpc);
		logger.info("newLotNumber " + newLotNumber);

	/*	List<Path> customerFolders=perfiosService.fetchCustomersOnLot(inputProcessingDirectory,lcpc);
		if (customerFolders.size()==0) {
			logger.info("No records found for the LCPC  "+lcpc);
			return;
		}
		logger.info("No of customer folders found " + customerFolders.size());*/

		
		
		
		
		
		
		
		
		Path zipOutputDir = Paths.get(zipOutputDirPath);
		Files.createDirectories(zipOutputDir);

		String lotNo="LOT"+newLotNumber;//lotNumber.split("_")[4];
		logger.info("lot to process now " + lotNo);

		Path tempZip = zipOutputDir.resolve(lcpc+"_"+ lotNo + "_" + currentDate + ".zip");
		logger.info("tempZip" + tempZip);
		
		logger.info("lcpc" + lcpc +" lotNo"+lotNo);

		Path csvCustTempFile=zipOutputDir.resolve(lcpc+"_"+ lotNo + "_"+currentDate+"_CustomerList.csv");
		logger.info("csv Cust TempFile" + csvCustTempFile);
		
		Path csvTempZipFile = zipOutputDir.resolve(lcpc+"_"+ lotNo + "_" + currentDate + "_Details.zip");
		logger.info("csvTempZipFile" + csvTempZipFile);

		String lotNameToSend =lcpc+"_"+ lotNo + "_" + currentDate;
		logger.info("lotNameToSend " + lotNameToSend);
		
		
		
		
		//Start of CR 26100024
		Path cbsDataDir = Paths.get(cbsOutputDirectoryPath);
		Files.createDirectories(cbsDataDir);
		String cbsCsvName = "CBS_DUMP_" + currentDate + ".csv";
		String cbsZipName = "CBS_DUMP_" + currentDate + ".zip";
		Path cbsZip = cbsDataDir.resolve(cbsZipName);
		LocalDate cbsDay = LocalDate.parse(currentDate, DateTimeFormatter.ofPattern("ddMMyyyy"));
		//End of CR 26100024
		
		try {
			List<CustomerCsvRecord> csvRecords = new ArrayList<>();
			List<CustomerCsvList> csvCustList = new ArrayList<>();

			String password=getPassword();
			ZipParameters zipParameters = new ZipParameters();
			zipParameters.setEncryptFiles(true);
			zipParameters.setEncryptionMethod(EncryptionMethod.AES);
			zipParameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);

			try (ZipFile zipFile = new ZipFile(tempZip.toFile(), password.toCharArray())) {
				//				Path csvFile = tempDir.resolve("MUM_LOT0_.csv");
				for (Path customerFolder : customerFolders) {
					String customerId = customerFolder.getFileName().toString();
					logger.info("CustomerId : " + customerId);
					
					if(!(Files.exists(customerFolder)&&Files.isDirectory(customerFolder))) {
						logger.info("Files exists > "+Files.exists(customerFolder)+"\t Files.isDirectory > "+Files.isDirectory(customerFolder));
						continue;
					}
					
					Path claimed = claimFolder(customerFolder, processingDir);					
					if (claimed == null)  continue;
					
					CleanFolder = new File(cleanFolder + "/" + lcpc+ "/" +customerId);
					logger.info("CleanFolder Path is "+ CleanFolder.getAbsolutePath());
					
					if (!(CleanFolder.exists())) CleanFolder.mkdir();
					
					ExceptionFold = new File(exceptionFolder + "/" + lcpc+ "/" + customerId);
					logger.info("Exception Folder Path is " + ExceptionFold.getAbsolutePath());
					
					if (!ExceptionFold.exists()) {
						logger.info("The Exception Folder does not exists");
						ExceptionFold.mkdir();
					}
					String[] folderValues=customerId.split("_");
					Customer customer = new Customer(folderValues[0],folderValues[1],folderValues[2]);
					try {
						FolderIdentifier.parse(customerId);
					} catch (IllegalArgumentException badName) {
						//	remarks="INVALID_FOLDER_NAME: " + badName.getMessage();
						moveValidatedFolderToClean(claimed, ExceptionFold.toPath());
						deleteFolderFromProcessing(claimed);
						continue;
					}
					boolean zipCreateStatus = perfiosService.checkStatusInPerfiosTable(customer.cifNumber());
					logger.info("cif status in perfios table " + zipCreateStatus);
					if (!zipCreateStatus) {
						logger.info("ZIP already created for a CIF number {}",customer.cifNumber());
						moveValidatedFolderToClean(claimed, ExceptionFold.toPath());
						deleteFolderFromProcessing(claimed);
						continue;
					}
					boolean cifNotUnderProcess=perfiosService.checkStatusInMainTables(customer.cifNumber());
					logger.info("cifNotUnderProcess " + cifNotUnderProcess);
					if (!cifNotUnderProcess) {
						logger.info("CIF already under processing for {}", customer.cifNumber());
						moveValidatedFolderToClean(claimed, ExceptionFold.toPath());
						deleteFolderFromProcessing(claimed);
						continue;
					}
					logger.info("customerFolder : " + processingDir);
					if (!Files.isDirectory(processingDir)) {
						throw new IllegalStateException("Lot " +lotNo+": output folder missing for cif " +customer.cifNumber()+" ("+customerFolder+") despite status=PROCESSED");
					}
					logger.info("Clean Folder Path : " + CleanFolder.getAbsolutePath());                  
					Path cleanTarget = moveValidatedFolderToClean(claimed, CleanFolder.toPath());
					logger.info("Customer folder pushed to clean location {}", cleanTarget);
					//logger.info("cleanTarget: " + cleanTarget.getFileName());

					try {
						zipFile.addFolder(cleanTarget.toFile(), zipParameters);
						logger.info("Folder added successfully");
					}catch(IOException e) {
						logger.error("Error while adding folder to the zip",e);
					}
					//Modified
					logger.info("Before adding in the csvRecords: " + csvRecords.size());

					csvRecords.add(perfiosService.fetchForCsv(customer.cifNumber(),customer.accountNumber()));
					logger.info("After adding in the csvRecords: " + csvRecords.size());

					csvCustList.add(new CustomerCsvList(lotNameToSend, customer.cifNumber(), customer.accountNumber()));
					logger.info("After adding in the csvCustList: " + csvCustList.size());

					deleteFolderFromProcessing(claimed);
					logger.info("Iterated for the customer  "+customer);
				}
				writeCustCsv(csvCustTempFile, csvCustList);
				logger.info("After adding in the csvCustTempFile: " + csvCustTempFile);
				//zipFile.addFile(csvTempFile.toFile(), zipParameters);
			}
			logger.info("__________________________ "); 
			
			boolean detailsHasData = !csvRecords.isEmpty();
			logger.info("details has Data >> "+detailsHasData);
			boolean custHasData = hasDataRows(csvCustTempFile);
			logger.info("customer list has Data >> "+custHasData);
			
			if(!detailsHasData&&!custHasData) {
				logger.info("no records and customer csv has only header. Deleting temporary csv file ");
				Files.deleteIfExists(csvCustTempFile);
				return;
			}
			
			//try (ZipFile zipcsvFile = new ZipFile(csvTempZipFile.toString(),password.toCharArray())) {
			try (ZipFile zipcsvFile = new ZipFile(csvTempZipFile.toFile(),password.toCharArray())) {
				logger.info("zip csv file \n "+csvCustTempFile.toFile());
				
				zipParameters.setFileNameInZip(csvCustTempFile.getFileName().toString());
				zipcsvFile.addFile(csvCustTempFile.toFile(), zipParameters);
			}
			logger.info("!!!!!! ");

			Files.deleteIfExists(csvCustTempFile);

			Path finalZip = zipOutputDir.resolve(tempZip);
			logger.info("finalZip "+finalZip);
			logger.info("tempzip " + tempZip);
			try{
				logger.info("tempZip exists "+ Files.exists(tempZip));
				logger.info("tempzip size :" + Files.size(tempZip));
				Files.move(tempZip, finalZip, StandardCopyOption.ATOMIC_MOVE);
				logger.info("finalZip "+finalZip);
			}catch(Exception e) {
				logger.info("Inside catch "+e.getMessage());
				//e.printStackTrace();
			}
			Path finalCsvZip = zipOutputDir.resolve(csvTempZipFile);
			Files.move(csvTempZipFile, finalCsvZip, StandardCopyOption.ATOMIC_MOVE);
			logger.info("finalCsvZip "+finalCsvZip);

			// CR 26100024: append this lot's details records to the day's password protected zip in cbs_data
			int cbsAdded = appendToCbsZip(cbsZip, cbsCsvName, csvRecords, getPassword(cbsDay).toCharArray());
			logger.info("CBS records added to {} : {}", cbsZipName, cbsAdded);

			perfiosService.updateStatusAndHistory(csvRecords,finalZip.getFileName(),newLotNumber,lcpc);

			if (cbsAdded > 0) {
				try {
					perfiosService.insertCbsRecord(java.sql.Date.valueOf(cbsDay), lotNo, cbsZipName, lcpc, cbsAdded);
				} catch (Exception e) {
					logger.error("CBS DB entry FAILED (zip already updated). Insert manually: date={}, lotNo={}, file={}, lcpc={}, records={}",
							cbsDay, lotNo, cbsZipName, lcpc, cbsAdded, e);
				}
			}

			logger.info("End of functionality ");
		}catch(Exception e) {
			logger.info("Inside catch ",e);
			e.printStackTrace();
			// do not swallow: lets processAvailableCustomer stop instead of re-fetching the same customers forever
			throw new IOException("zipCreate failed for lcpc " + lcpc, e);
		}
	}

	private boolean hasDataRows(Path csvFile) throws IOException {
		try(Stream<String> lines=Files.lines(csvFile)){
			return lines.skip(1).anyMatch(line->!line.trim().isEmpty());
		}
	}

	private void deleteFolderFromProcessing(Path processing) {
		if (processing == null || !Files.exists(processing)) return; 
		try {
			Files.walkFileTree(processing, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}
				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					if (exc != null) throw exc;
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			throw new RuntimeException("Failed to delete folder: " + processing, e);
		}
		logger.info("removed: " + processing.toString());
	}

	private static String parsedCurrentdate() {
		LocalDate today = LocalDate.now(ZoneId.systemDefault());
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyy");
		return today.format(formatter);
	}

	private void writeCustCsv(Path csvCustTempFile, List<CustomerCsvList> csvCustList) {
		logger.info("Inside writeCSV");
		StringBuilder sb = new StringBuilder();
		logger.info("sb: " + sb.toString());

		sb.append("LOT_NAME,CIF_NUMBER,ACCOUNT_NUMBER").append(System.lineSeparator());
		for (CustomerCsvList r : csvCustList) {
			sb.append(csvEscape(r.lotFileName())).append(',')
			.append(csvEscape(r.cif())).append(',')
			.append(csvEscape(r.accountNumber())).append(',')
			.append(System.lineSeparator());
		}

		logger.info("sb after appending entries: "/* ,sb.toString() */);
		try {
			Files.writeString(csvCustTempFile, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		}catch(Exception e) {
			logger.info("csv Write exception: ",e);
		}
	}
	
	// ===================== CR 26100024 : CBS daily zip =====================

	private static final String CBS_HEADER = "CIF_NUMBER,ACCOUNT_NUMBER,DATE_OF_BIRTH,FIRST_NAME,MIDDLE_NAME,LAST_NAME,ADDRESS";
	private static final long CBS_LOCK_WAIT_MS = 10 * 60 * 1000L;   // max wait for another run to finish its append
	private static final long CBS_LOCK_STALE_MS = 15 * 60 * 1000L;  // lock older than this = crashed run

	/**
	 * Adds the given records to the day's CSV that lives inside the password protected zip in cbs_data.
	 * Flow: lock -> extract existing csv from zip (if any) -> append only new rows -> build new zip as .tmp -> swap.
	 * The existing zip is never touched until the new one is fully written and validated.
	 * @return number of rows actually added (blank and already-present rows are skipped)
	 */
	private int appendToCbsZip(Path cbsZip, String cbsCsvName, List<CustomerCsvRecord> records, char[] pwd) throws IOException {
		if (records == null || records.isEmpty()) return 0;

		Path dir = cbsZip.getParent();
		Path lock = dir.resolve(cbsZip.getFileName() + ".lock");
		Path tmpZip = dir.resolve(cbsZip.getFileName() + ".tmp");
		acquireCbsLock(lock);
		Path work = null;
		Path csv = null;
		try {
			work = Files.createTempDirectory(dir, "cbs_work_");
			csv = work.resolve(cbsCsvName);

			Set<String> existingKeys = new HashSet<>();
			if (Files.exists(cbsZip)) {
				try (ZipFile z = new ZipFile(cbsZip.toFile(), pwd)) {
					z.extractFile(cbsCsvName, work.toString());
				}
				existingKeys = loadCbsKeys(csv);
				logger.info("Existing CBS rows in {} : {}", cbsZip.getFileName(), existingKeys.size());
			}

			boolean newCsv = Files.notExists(csv);
			int added = 0;
			int skipped = 0;
			try (BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				if (newCsv) {
					w.write(CBS_HEADER);
					w.newLine();
				}
				for (CustomerCsvRecord r : records) {
					if (r == null || r.cifNumber() == null || r.cifNumber().trim().isEmpty()) {
						skipped++;
						continue;
					}
					if (!existingKeys.add(cbsKey(r.cifNumber(), r.accountNumber()))) {
						skipped++;
						continue;
					}
					w.write(toCbsLine(r));
					w.newLine();
					added++;
				}
			}
			logger.info("CBS rows added {} , skipped (blank/duplicate) {}", added, skipped);
			if (added == 0) return 0;

			Files.deleteIfExists(tmpZip);
			ZipParameters zp = new ZipParameters();
			zp.setEncryptFiles(true);
			zp.setEncryptionMethod(EncryptionMethod.AES);
			zp.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
			zp.setCompressionLevel(CompressionLevel.FAST);
			zp.setFileNameInZip(cbsCsvName);
			try (ZipFile z = new ZipFile(tmpZip.toFile(), pwd)) {
				z.addFile(csv.toFile(), zp);
				if (!z.isValidZipFile()) {
					throw new IOException("New CBS zip failed validation: " + tmpZip);
				}
			}
			Files.move(tmpZip, cbsZip, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			logger.info("CBS zip updated : {}", cbsZip);
			return added;
		} finally {
			try { if (csv != null) Files.deleteIfExists(csv); } catch (IOException e) { logger.warn("cleanup csv failed: {}", e.getMessage()); }
			try { if (work != null) Files.deleteIfExists(work); } catch (IOException e) { logger.warn("cleanup work dir failed: {}", e.getMessage()); }
			try { Files.deleteIfExists(tmpZip); } catch (IOException e) { logger.warn("cleanup tmp zip failed: {}", e.getMessage()); }
			try { Files.deleteIfExists(lock); } catch (IOException e) { logger.warn("could not release lock {}: {}", lock, e.getMessage()); }
		}
	}

	private Set<String> loadCbsKeys(Path csv) throws IOException {
		Set<String> keys = new HashSet<>();
		if (Files.notExists(csv)) return keys;
		try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
			br.readLine(); // header
			String line;
			while ((line = br.readLine()) != null) {
				if (line.isEmpty()) continue;
				String[] p = line.split(",", 3);
				if (p.length >= 2) keys.add(cbsKey(unquote(p[0]), unquote(p[1])));
			}
		}
		return keys;
	}

	private static String cbsKey(String cif, String account) {
		return (cif == null ? "" : cif.trim()) + "|" + (account == null ? "" : account.trim());
	}

	private static String unquote(String v) {
		if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) return v.substring(1, v.length() - 1);
		return v;
	}

	// same column order / escaping as the previous details csv
	private String toCbsLine(CustomerCsvRecord r) {
		return new StringBuilder(256)
				.append(csvEscape(r.cifNumber())).append(',')
				.append(csvEscape(r.accountNumber())).append(',')
				.append(csvEscape(r.dateOfBirth())).append(',')
				.append(csvEscape(r.firstName())).append(',')
				.append(csvEscape(r.middleName())).append(',')
				.append(csvEscape(r.lastName())).append(',')
				.append(csvEscape(r.address())).append(',')
				.toString();
	}

	// lock file created atomically (works across servers sharing the folder); stale locks from crashed runs are removed
	private void acquireCbsLock(Path lock) throws IOException {
		long deadline = System.currentTimeMillis() + CBS_LOCK_WAIT_MS;
		while (true) {
			try {
				Files.createFile(lock);
				try {
					Files.writeString(lock, hostId + " " + Instant.now());
				} catch (IOException ignore) { /* informational only */ }
				return;
			} catch (FileAlreadyExistsException e) {
				try {
					long age = System.currentTimeMillis() - Files.getLastModifiedTime(lock).toMillis();
					if (age > CBS_LOCK_STALE_MS) {
						logger.warn("Removing stale CBS lock {} (age {} ms)", lock, age);
						Files.deleteIfExists(lock);
						continue;
					}
				} catch (NoSuchFileException gone) {
					continue; // released just now, retry immediately
				}
				if (System.currentTimeMillis() > deadline) {
					throw new IOException("Timed out waiting for CBS lock " + lock);
				}
				try {
					Thread.sleep(2000L);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for CBS lock", ie);
				}
			}
		}
	}


	private String csvEscape(String value) {
		if (value == null) return "";
		
		if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
			return "\"" + value.replace("\"", "\"\"") + "\"";
		}
		return value;
	}

	private static String getPassword() {
		return getPassword(LocalDate.now());
	}

	private static String getPassword(LocalDate now) {
		int month = now.getMonthValue();
		int weekOfMonth = now.get(WeekFields.of(Locale.getDefault()).weekOfMonth());
		int pass = month * 10 + weekOfMonth;
		return "password@" + pass;
	}


	private record CustomerCsvList(String lotFileName, String cif, String accountNumber) { }
	//private record FolderWithTime(Path path, long lastModifiedMillis) { }


	private Path claimFolder(Path sourceFolder, Path processingDir) {
		logger.info("sourceFolder Path : " + sourceFolder);
		String customerId = sourceFolder.getFileName().toString();
		Path target = processingDir.resolve(customerId);
		logger.info("Target Path : " + target);
		try {
			Files.createDirectories(processingDir);
			Path moved = Files.move(sourceFolder, target, StandardCopyOption.ATOMIC_MOVE);
			logger.info("Path moved : "+moved);
			writeClaimMarker(moved);
			return moved;
		} catch (NoSuchFileException e) {
			logger.error("Skipping {} - already claimed or conflicting target exists", customerId);
			logger.error("NoSuchFileException "+e.getMessage());
			return null;
		} catch (  FileAlreadyExistsException e) {
			logger.error("Skipping {} - already claimed or conflicting target exists", customerId);
			logger.error("FileAlreadyExistsException  "+e.getMessage());
			return null;
		} catch (IOException e) {
			logger.error("Failed to claim folder {}: {}", customerId, e.getMessage(), e);
			e.printStackTrace();
			return null;
		}
	}

	private void writeClaimMarker(Path claimedFolder) {
		Path marker = claimedFolder.resolve(".claim");
		String content = "host=" + hostId + System.lineSeparator() + "claimedAt=" + Instant.now() + System.lineSeparator();
		logger.info("content to write "+content);
		try {
			Files.writeString(marker, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			logger.error("Could not write claim marker for {}: {}", claimedFolder, e.getMessage());
		}
	}

	private Path moveValidatedFolderToClean(Path claimedFolder, Path cleanTargetFolder) {   
		logger.info("Inside move FolderToClean");
		try {
			Files.createDirectories(cleanTargetFolder);
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(claimedFolder)) {
				for (Path file : stream) {
					if (file.getFileName().toString().equals(".claim")) {
						continue; 
					}
					copyForNfs(file, cleanTargetFolder.resolve(file.getFileName()));
				}                                                                
			}
			return cleanTargetFolder;
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to push validated folder to clean: " + claimedFolder, e);
		}
	}

	private void copyForNfs(Path source, Path target) throws IOException {
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		IOException last = null;
		for (int attempt = 1; attempt <= 3; attempt++) {
			try {
				Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (IOException e) {
				last = e;
				logger.error("NFS copy attempt {} failed for {}: {}", attempt, source, e.getMessage());
				sleepBackoff(attempt);
			}
		}
		throw last;
	}

	private void sleepBackoff(int attempt) throws IOException {
		try {
			Thread.sleep(200L * attempt);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted during NFS retry backoff", ie);
		}
	}
}
