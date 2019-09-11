/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package edu.iupui.ulib.dspace.ctask.replicate.store;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.dspace.ctask.replicate.ObjectStore;
import org.dspace.curate.Utils;
import org.dspace.pack.bagit.Bag;
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
public class APTrustObjectStore implements ObjectStore {
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

        try {
             // DEBUG REMOVE
            long start = System.currentTimeMillis();
            Content content = store.getContent(getSpaceID(group), getContentPrefix(group) + id);
            // DEBUG REMOVE
            long elapsed = System.currentTimeMillis() - start;
            //System.out.println("DC fetch content: " + elapsed);
            size = Long.valueOf(content.getProperties().get(ContentStore.CONTENT_SIZE));
            FileOutputStream out = new FileOutputStream(file);
            // DEBUG remove
            start = System.currentTimeMillis();
            InputStream in = content.getStream();
            Utils.copy(in, out);
            in.close();
            out.close();
             // DEBUG REMOVE
            elapsed = System.currentTimeMillis() - start;
            //System.out.println("DC fetch download: " + elapsed);
        } catch (NotFoundException nfE) {
            // no object - no-op
        } catch (ContentStoreException csE) {
            throw new IOException(csE);
        }
        return size;
    }

    @Override
    public boolean objectExists(String group, String id) throws IOException {
        try {
            return store.getContentProperties(getSpaceID(group), getContentPrefix(group) + id) != null;
        } catch (NotFoundException nfE) {
            return false;
        } catch (ContentStoreException csE) {
            throw new IOException(csE);
        }
    }

    @Override
    public long removeObject(String group, String id) throws IOException {
        // get metadata before blowing away
        long size = 0L;
        try {
            Map<String, String> attrs = store.getContentProperties(getSpaceID(group), getContentPrefix(group) + id);
            size = Long.valueOf(attrs.get(ContentStore.CONTENT_SIZE));
            store.deleteContent(getSpaceID(group), getContentPrefix(group) + id);
        } catch (NotFoundException nfE) {
            // no replica - no-op
        } catch (ContentStoreException csE) {
            throw new IOException(csE);
        }
        return size;
    }

    @Override
    public long transferObject(String group, File file) throws IOException {
        // build bag
        File bagDir = Files.createTempDirectory(null).toFile();
        Bag myBag = new Bag(bagDir);
        myBag.addData("", file.length(), new FileInputStream(file));
        myBag.close();
        File bagFile = File.createTempFile("APT", ".tgz");
        myBag.deflate(bagFile.getAbsolutePath(), "tgz");
        myBag.empty();

        // Send it to our S3 bucket.
        long size = 0L;
        String chkSum = Utils.checksum(file, "MD5");
        size = uploadReplica(group, file, chkSum);

        // delete staging files
        bagFile.delete();
        file.delete();

        return size;
    }

    @Override
    public long moveObject(String srcGroup, String destGroup, String id) throws IOException {
        // get file-size metadata before moving the content
        long size = 0L;
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
        return size;
    }

    @Override
    public String objectAttribute(String group, String id, String attrName)
            throws IOException {
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
            String mimeType = "application/octet-stream";
            if (file.getName().endsWith(".zip")) {
                mimeType = "application/zip";
            } else if (file.getName().endsWith(".tgz")) {
                mimeType = "application/x-gzip";
            } else if (file.getName().endsWith(".txt")) {
                mimeType = "text/plain";
            }

            // TODO check whether file is already stored?

            Map<String, String> metadata = new HashMap<>();
            metadata.put("checksum", chkSum);
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(makeObjectName(group, file))
                    .contentType(mimeType)
                    .metadata(metadata)
                    .build();
            PutObjectResponse response = store.putObject(putObjectRequest, file.toPath());

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
}
