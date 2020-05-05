/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package edu.iupui.ulib.dspace.ctask.replicate.store;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import gov.loc.repository.bagit.domain.Bag;
import gov.loc.repository.bagit.domain.Manifest;
import gov.loc.repository.bagit.domain.Metadata;
import gov.loc.repository.bagit.domain.Version;
import gov.loc.repository.bagit.hash.Hasher;
import gov.loc.repository.bagit.hash.StandardSupportedAlgorithms;
import gov.loc.repository.bagit.hash.SupportedAlgorithm;
import gov.loc.repository.bagit.writer.BagWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.dspace.ctask.replicate.ObjectStore;
import org.dspace.curate.Utils;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Store and retrieve objects at APTrust using the AWS S3 RESTful web service.
 * Based on {@link org.dspace.ctask.replicate.store.DuraCloudObjectStore}.
 *
 * @author Mark H. Wood
 */
public class APTrustObjectStore
        implements ObjectStore {
    private final ConfigurationService configurationService
            = DSpaceServicesFactory.getInstance().getConfigurationService();

    /** AWS S3 bucket for staging to APTrust. */
    private String bucket;

    /** AWS S3 store. */
    private S3Client store = null;

    @Override
    public void init() throws IOException {
        bucket = configurationService.getProperty("aptrust.aws.bucket");

        // locate & login to S3 bucket.
        String accessKey
                = configurationService.getProperty("aptrust.aws.access-key");
        String secretAccessKey
                = configurationService.getProperty("aptrust.aws.secret-access-key");
        AwsCredentials awsCredentials
                = AwsBasicCredentials.create(accessKey, secretAccessKey);
        AwsCredentialsProvider acp
                = StaticCredentialsProvider.create(awsCredentials);

        store = S3Client.builder()
                .credentialsProvider(acp)
                .build();
    }

    @Override
    public long fetchObject(String group, String id, File file)
            throws IOException {
        long size = 0L;

        // Request retrieval.
        // Await completion of retrieval.
        // Fetch retrieved object.

        /* TODO implement fetchObject
        try {
            Content content = store.getContent(getSpaceID(group), getContentPrefix(group) + id);
            size = Long.valueOf(content.getProperties().get(ContentStore.CONTENT_SIZE));
            FileOutputStream out = new FileOutputStream(file);
            InputStream in = content.getStream();
            Utils.copy(in, out);
            in.close();
            out.close();
        } catch (NotFoundException nfE) {
            // no object - no-op
        } catch (ContentStoreException csE) {
            throw new IOException(csE);
        }
        */
        return size;
    }

    @Override
    public boolean objectExists(String group, String id) throws IOException {
        /* TODO implement objectExists
        try {
            return store.getContentProperties(getSpaceID(group), getContentPrefix(group) + id) != null;
        } catch (NotFoundException nfE) {
            return false;
        } catch (ContentStoreException csE) {
            throw new IOException(csE);
        }
        */ return true;
    }

    @Override
    public long removeObject(String group, String id) throws IOException {
        // get metadata before blowing away
        long size = 0L;
        /* TODO implement removeObject
        try {
            Map<String, String> attrs = store.getContentProperties(getSpaceID(group), getContentPrefix(group) + id);
            size = Long.valueOf(attrs.get(ContentStore.CONTENT_SIZE));
            store.deleteContent(getSpaceID(group), getContentPrefix(group) + id);
        } catch (NotFoundException nfE) {
            // no replica - no-op
        } catch (ContentStoreException csE) {
            throw new IOException(csE);
        }
        */
        return size;
    }

    @Override
    public long transferObject(String group, File file) throws IOException {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

        // Build a bag around the file.
        File baggingDir = Files.createTempDirectory(null).toFile();
        String bagName = bucket.substring(0, bucket.lastIndexOf('/'))
                + '.' + baggingDir.getName();
        Path bagDir = Paths.get(baggingDir.getPath(), bagName);

        Bag myBag = new Bag(new Version(0, 97));
        myBag.setFileEncoding(StandardCharsets.UTF_8);
        myBag.setRootDir(bagDir);

        // Initialize payload manifests.
        List<SupportedAlgorithm> hashAlgorithms = Arrays.asList(
                StandardSupportedAlgorithms.MD5,
                StandardSupportedAlgorithms.SHA256);

        // Add the single payload file to the manifests.
        Map<Manifest, MessageDigest> hashesMap
                = Hasher.createManifestToMessageDigestMap(hashAlgorithms);
        Hasher.hash(file.toPath(), hashesMap);

        myBag.setPayLoadManifests(hashesMap.keySet());

        // We need a bag-info.txt tag file.  LoC Bagit implements this.
        Metadata bagInfo = new Metadata();
        bagInfo.add("Source-Organization", "iupui.edu");
        bagInfo.add("Bagging-Date", dateFormat.format(new Date()));
        bagInfo.add("Bag-Count", "1 of 1");
        bagInfo.add("Internal-Sender-Description", "");
        bagInfo.add("Internal-Sender-Identifier", baggingDir.getName());
        bagInfo.add("Bag-Group-Identifier", "");
        myBag.setMetadata(bagInfo);

        // We need an aptrust-info.txt tag file.  Must roll our own.
        Metadata aptrustInfo = new Metadata();
        aptrustInfo.add("Title", file.getName());
        aptrustInfo.add("Description", "Backup by replication task suite");
        aptrustInfo.add("Access", "Institution");
        aptrustInfo.add("Storage-Option", "Standard");
        new FileWriter(new File(bagDir.toFile(), "aptrust-info.txt"))
                .append(aptrustInfo.toString())
                .close();

        try {
            // Create the bag in storage.
            BagWriter.write(myBag, bagDir);
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("Could not write bag to storage.", ex);
        }

        // Archive the bag.
        File bagArchive = new File(baggingDir, bagName + ".tar");
        try (TarArchiveOutputStream archive
                = new TarArchiveOutputStream(new FileOutputStream(bagArchive))) {
            Files.walkFileTree(bagDir, new BagFileVisitor(archive, baggingDir.toPath()));

            ArchiveEntry fileEntry = archive.createArchiveEntry(file,
                    Paths.get(bagName, "data", file.getName()).toString());
            archive.putArchiveEntry(fileEntry);
            file.delete();
        }
        Files.delete(bagDir);

        // Send the archive to our S3 bucket.
        String chkSum = Utils.checksum(file, "MD5");
        long size = uploadReplica(group, bagArchive, chkSum);
        bagArchive.delete();
        baggingDir.delete();

        return size;
    }

    @Override
    public long moveObject(String srcGroup, String destGroup, String id) throws IOException {
        // get file-size metadata before moving the content
        long size = 0L;
        /* TODO implement moveObject
        try {
            Map<String, String> attrs = store.getContentProperties(getSpaceID(srcGroup),
                    getContentPrefix(srcGroup) + id);
            size = Long.valueOf(attrs.get(ContentStore.CONTENT_SIZE));
            store.moveContent(getSpaceID(srcGroup), getContentPrefix(srcGroup) + id,
                                getSpaceID(destGroup), getContentPrefix(destGroup) + id);
        } catch (NotFoundException nfE) {
            // no replica - no-op
        } catch (ContentStoreException csE) {
            throw new IOException(csE);
        }
        */
        return size;
    }

    @Override
    public String objectAttribute(String group, String id, String attrName)
            throws IOException {
        /* TODO implement objectAttribute
        try {
            Map<String, String> attrs = store.getContentProperties(getSpaceID(group),
                    getContentPrefix(group) + id);

            if ("checksum".equals(attrName)) {
                return attrs.get(ContentStore.CONTENT_CHECKSUM);
            } else if ("sizebytes".equals(attrName)) {
                return attrs.get(ContentStore.CONTENT_SIZE);
            } else if ("modified".equals(attrName)) {
                return attrs.get(ContentStore.CONTENT_MODIFIED);
            }
            return null;
        } catch (NotFoundException nfE) {
            return null;
        } catch (ContentStoreException csE) {
            throw new IOException(csE);
        }
        */ return attrName;
    }

    /**
     * Transmit a file to S3 for staging into APTrust.  The file is NOT
     * preserved when this method returns -- you must monitor the APTrust member
     * API events collection to determine its status.
     *
     * @param group purpose of the file.
     * @param file the file to be staged.
     * @param chkSum a checksum of the content of the file, added to the S3
     *                  object as the metadata field "checksum".
     * @return length of the file.
     * @throws IOException
     */
    private long uploadReplica(String group, File file, String chkSum)
            throws IOException {
        try {
            // TODO check whether file is already stored?
            Map<String, String> metadata = new HashMap<>();
            metadata.put("checksum", chkSum);
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(makeObjectName(group, file))
                    .contentType("application/tar")
                    .metadata(metadata)
                    .build();
            PutObjectResponse response = store.putObject(putObjectRequest, file.toPath()); // TODO response?
            response.

            return file.length();
        } catch (AwsServiceException | SdkClientException e) {
            throw new IOException("File " + file.getAbsolutePath() + "not uploaded.", e);
        }
    }

    /**
     * Compose an S3 object name from prefix and file name.
     *
     * @param group prefix for this kind of file.
     * @param file file within the group.
     * @return name combining group and file name, always unique for any unique
     *          combination of inputs.
     */
    private String makeObjectName(String group, File file) {
        return new StringBuilder(group)
                .append('/')
                .append(file.getName())
                .toString();
    }

    /**
     * Add a file to a {@code tar} archive and delete the original file.
     */
    class BagFileVisitor
            extends SimpleFileVisitor<Path> {
        private final TarArchiveOutputStream archive;
        private final Path rootDir;

        /**
         * Capture the archive and root directory for use by {@link visitFile}.
         * @param archive
         * @param rootDir
         */
        public BagFileVisitor(TarArchiveOutputStream archive, Path rootDir) {
            this.archive = archive;
            this.rootDir = rootDir;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
            ArchiveEntry entry = archive.createArchiveEntry(file.toFile(),
                    file.relativize(rootDir).toString());
            archive.putArchiveEntry(entry);
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }
    }
}
