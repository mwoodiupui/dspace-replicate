/*
 * Copyright 2019 Indiana University.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 * {@link org.dspace.ctask.replicate.store.ObjectStore} implementation for
 * APTrust.
 *
 * <p>The APTrust workflow presents significant challenges.  Deposit into APTrust
 * starts with bagging the "intellectual object" (here, a DSpace AIP) and dropping
 * it into an Amazon S3 bucket.  Eventually an archive service notices the new
 * S3 object, unpacks it, replicates it into various long-term storage facilities,
 * and records it in a database.  Thus, the client never speaks to the APTrust
 * service during this operation, only to AWS.
 *
 * <p>The only way to know whether the intellectual object
 * is preserved by APTrust is to monitor the events generated by its advance
 * through the archival process, using the APTrust Member API.  One can poll for
 * new events to see whether a deposit has completed.  Use the
 * {@code /member-api/v2/items} endpoint.
 * <strong>I don't yet know how to identify a specific deposit in APTrust event
 * records.</strong>
 * It may be that we can identify the object by the bucket/bag-archive path.
 * (intellectual_object_identifier or generic_file_identifier)
 *
 * <p>Retrieval is similar, in that one sends a request to APTrust to retrieve
 * the intellectual object, monitors the progress of the request and, when
 * complete, fetches a bag from the same S3 bucket.  One unpacks the bag and
 * (here) restores the enclosed AIP.
 * <strong>How to identify the required intellectual object is not yet known.</strong>
 *
 * <p>Other operations such as deletion seem to involve only the APTrust API.
 */
package edu.iupui.ulib.dspace.ctask.replicate.store;