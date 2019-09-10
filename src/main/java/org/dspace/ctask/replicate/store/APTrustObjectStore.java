/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.replicate.store;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

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

/**
 * Store and retrieve objects at APTrust using the AWS S3 RESTful web service.
 * Based on {@link DuraCloudObjectStore}.
 *
 * @author Mark H. Wood
 */
public class APTrustObjectStore implements ObjectStore
{
    private final ConfigurationService configurationService
            = DSpaceServicesFactory.getInstance().getConfigurationService();

    // AWS S3 bucket for staging to APTrust.
    private String bucket;

    // AWS S3 store.
    private S3Client store = null;

    @Override
    public void init() throws IOException
    {
        bucket = configurationService.getProperty("aptrust.aws.bucket");

        // locate & login to S3 bucket.
        String accessKey
                = configurationService.getProperty("aptrust.aws.access-key");
        String secretAccessKey
                = configurationService.getProperty("aptrust.aws.secret-access-key");
        AwsCredentials awsCredentials = AwsBasicCredentials.create(accessKey, secretAccessKey);
        AwsCredentialsProvider acp
                = StaticCredentialsProvider.create(awsCredentials);

        store = S3Client.builder()
                .credentialsProvider(acp)
                .build();
    }

    @Override
    public long fetchObject(String group, String id, File file) throws IOException
    {
        long size = 0L;
        try
        {
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
    public boolean objectExists(String group, String id) throws IOException
    {
        try {
            return store.getContentProperties(getSpaceID(group), getContentPrefix(group) + id) != null;
        } catch (NotFoundException nfE) {
            return false;
        } catch (ContentStoreException csE) {
            throw new IOException(csE);
        }
    }

    @Override
    public long removeObject(String group, String id) throws IOException
    {
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
    public long transferObject(String group, File file) throws IOException
    {
        long size = 0L;
        String chkSum = Utils.checksum(file, "MD5");
        // make sure this is a different file from what replica store has
        // to avoid network I/O tax
        try {
            Map<String, String> attrs = store.getContentProperties(getSpaceID(group),
                    getContentPrefix(group) + file.getName());
            if (! chkSum.equals(attrs.get(ContentStore.CONTENT_CHECKSUM)))
            {
                size = uploadReplica(group, file, chkSum);
            }
        } catch (NotFoundException nfE) {
            // no extant replica - proceed
            size = uploadReplica(group, file, chkSum);
        } catch (ContentStoreException csE) {
            throw new IOException(csE);
        }
        // delete staging file
        file.delete();
        return size;
    }

    @Override
    public long moveObject(String srcGroup, String destGroup, String id) throws IOException
    {
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
    public String objectAttribute(String group, String id, String attrName) throws IOException
    {
        try {
            Map<String, String> attrs = store.getContentProperties(getSpaceID(group),
                    getContentPrefix(group) + id);

            if ("checksum".equals(attrName))
            {
                return attrs.get(ContentStore.CONTENT_CHECKSUM);
            }
            else if ("sizebytes".equals(attrName))
            {
                return attrs.get(ContentStore.CONTENT_SIZE);
            }
            else if ("modified".equals(attrName))
            {
                return attrs.get(ContentStore.CONTENT_MODIFIED);
            }
            return null;
        } catch (NotFoundException nfE) {
            return null;
        } catch (ContentStoreException csE) {
            throw new IOException(csE);
        }
    }

    private long uploadReplica(String group, File file, String chkSum)
            throws IOException
    {
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
            store.putObject(putObjectRequest, file.toPath());

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

    class APTrustSession {
        private final URI baseURI;
        private final String user;
        private final String secret;
        CloseableHttpClient httpClient;

        APTrustSession(URI baseURI, String user, String secret) {
            this.baseURI = baseURI;
            this.user = user;
            this.secret = secret;

            httpClient = HttpClients.createDefault();
        }

        boolean isExists(String institution, String bagName) {
            URI uri;
            try {
                uri = new URIBuilder(baseURI.resolve("objects"))
                        .addParameter("identifier", makeIdentifier(institution, bagName))
                        .build();
            } catch (URISyntaxException ex) {
                // FIXME handle exception
                return false;
            }
            HttpGet request = new HttpGet(uri);
            request.addHeader("X-Pharos-API-User", user);
            request.addHeader("X-Pharos-API-Key", secret);
            try ( CloseableHttpResponse response = httpClient.execute(request); ) {
                InputStream responseContent = response.getEntity().getContent();
                // TODO interpret response
            } catch (IOException ex) {
                // TODO handle exception
            }
            return false; // FIXME real answer
        }

        private String makeIdentifier(String institution, String bagName) {
            return null; // FIXME real answer
        }
    }
}
