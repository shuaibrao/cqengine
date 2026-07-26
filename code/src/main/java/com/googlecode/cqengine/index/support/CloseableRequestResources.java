/**
 * Copyright 2012-2015 Niall Gallagher
 * Modified by Shuaib Rao in 2026.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.cqengine.index.support;

import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;

import java.io.Closeable;
import java.util.*;

/**
 * A QueryOption that allows to keep track of query resources which were opened to process a request and
 * which need to be closed when processing of the request is finished.
 * <p>
 * The {@link #add(Closeable)} method is used to add {@link Closeable} objects to this object.
 * Then when processing the request has finished (often when {@link ResultSet#close()} is called), the engine will
 * retrieve this object from the query options and call the {@link #close()} method on this object, which will close
 * all resources which had been added.
 *
 * @author Silvano Riz
 */
public class CloseableRequestResources implements Closeable {

    final Collection<Closeable> requestResources = Collections.newSetFromMap(new IdentityHashMap<Closeable, Boolean>());
    private final Object requestResourcesLock = new Object();
    boolean closed;

    /**
     * Add a new resource that needs to be closed.
     *
     * @param closeable The resource that needs to be closed
     */
    public void add(Closeable closeable) {
        synchronized (requestResourcesLock) {
            if (closed) {
                throw new IllegalStateException("Request resources have already been closed");
            }
            requestResources.add(closeable);
        }
    }

    public CloseableResourceGroup addGroup() {
        CloseableResourceGroup group = new CloseableResourceGroup();
        add(group);
        return group;
    }

    /**
     * Close and removes all resources and resource groups which have been added so far.
     */
    @Override
    public void close() {
        final Collection<Closeable> resourcesToClose;
        synchronized (requestResourcesLock) {
            if (closed) {
                return;
            }
            closed = true;
            resourcesToClose = new ArrayList<Closeable>(requestResources);
            requestResources.clear();
        }
        closeAll(resourcesToClose);
    }

    /**
     * Returns an existing {@link CloseableRequestResources} from the QueryOptions, or adds a new
     * instance to the query options and returns that.
     *
     * @param queryOptions The {@link QueryOptions}
     * @return The existing QueryOptions's CloseableRequestResources or a new instance.
     */
    public static CloseableRequestResources forQueryOptions(final QueryOptions queryOptions) {
        CloseableRequestResources closeableRequestResources = queryOptions.get(CloseableRequestResources.class);
        if (closeableRequestResources == null) {
            closeableRequestResources = new CloseableRequestResources();
            queryOptions.put(CloseableRequestResources.class, closeableRequestResources);
        }
        return closeableRequestResources;
    }

    /**
     * Closes an existing {@link CloseableRequestResources} if one is stored the QueryOptions and then removes
     * it from the QueryOptions.
     *
     * @param queryOptions The {@link QueryOptions}
     */
    public static void closeForQueryOptions(QueryOptions queryOptions) {
        try {
            CloseableRequestResources resources = queryOptions.get(CloseableRequestResources.class);
            if (resources != null) {
                resources.close();
            }
        }
        finally {
            queryOptions.remove(CloseableRequestResources.class);
        }
    }

    /**
     * Closes every supplied resource and rethrows the first failure after attaching later failures as suppressed.
     *
     * @param closeables resources to close, in close order
     */
    public static void closeAll(Iterable<? extends Closeable> closeables) {
        Throwable failure = null;
        for (Closeable closeable : closeables) {
            if (closeable == null) {
                continue;
            }
            try {
                closeable.close();
            }
            catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure instanceof RuntimeException || closeFailure instanceof Error
                            ? closeFailure
                            : new IllegalStateException("Failed to close request resource", closeFailure);
                }
                else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
    }

    /**
     * Attempts to close a resource while preserving an exception which is already in flight.
     *
     * @param closeable resource to close
     * @param primaryFailure exception which must remain primary
     */
    public static void closeAndAddSuppressed(Closeable closeable, Throwable primaryFailure) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        }
        catch (Throwable closeFailure) {
            if (primaryFailure != closeFailure) {
                primaryFailure.addSuppressed(closeFailure);
            }
        }
    }

    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    public class CloseableResourceGroup implements Closeable {
        final Set<Closeable> groupResources = Collections.newSetFromMap(new IdentityHashMap<Closeable, Boolean>());
        private final Object groupResourcesLock = new Object();

        public boolean add(Closeable closeable) {
            synchronized (groupResourcesLock) {
                try {
                    CloseableRequestResources.this.add(this);
                }
                catch (RuntimeException | Error failure) {
                    closeAndAddSuppressed(closeable, failure);
                    throw failure;
                }
                return groupResources.add(closeable);
            }
        }

        /**
         * Closes all resources in this group, and then removes the group from the request resources.
         */
        @Override
        public void close() {
            final Collection<Closeable> resourcesToClose;
            synchronized (groupResourcesLock) {
                resourcesToClose = new ArrayList<Closeable>(groupResources);
                groupResources.clear();
                remove(this);
            }
            CloseableRequestResources.closeAll(resourcesToClose);
        }

        @Override
        public String toString() {
            synchronized (groupResourcesLock) {
                return groupResources.toString();
            }
        }
    }

    void remove(Closeable closeable) {
        synchronized (requestResourcesLock) {
            requestResources.remove(closeable);
        }
    }
}
