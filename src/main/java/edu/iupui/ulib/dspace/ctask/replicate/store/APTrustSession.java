/*
 * Copyright 2019 Indiana University.
 */

package edu.iupui.ulib.dspace.ctask.replicate.store;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

/**
 * Communicate with the APTrust service via HTTP.
 */
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
            uri = new URIBuilder(baseURI.resolve("objects")).addParameter("identifier",
                    makeIdentifier(institution, bagName)).build();
        } catch (URISyntaxException ex) {
            // FIXME handle exception
            return false;
        }
        HttpGet request = new HttpGet(uri);
        request.addHeader("X-Pharos-API-User", user);
        request.addHeader("X-Pharos-API-Key", secret);
        try (final CloseableHttpResponse response = httpClient.execute(request)) {
            InputStream responseContent = response.getEntity().getContent();
            // TODO interpret response
        } catch (IOException ex) {
            // TODO handle exception
        }

        return false; // FIXME real answer
    }

    private String makeIdentifier(String institution, String bagName) {
        return new StringBuilder(institution).append('/').append(bagName).toString();
    }
}
