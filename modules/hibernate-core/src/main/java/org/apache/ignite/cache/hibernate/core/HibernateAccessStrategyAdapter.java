/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.cache.hibernate.core;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.processors.cache.IgniteInternalCache;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.jetbrains.annotations.Nullable;

/**
 * Common interface used to implement Hibernate L2 cache access strategies.
 * <p>
 * The expected sequences of steps related to various CRUD operations executed by Hibernate are:
 * <p>
 * Insert:
 * <ul>
 *     <li>Start DB transaction.</li>
 *     <li>Execute database insert.</li>
 *     <li>Call {@link HibernateAccessStrategyAdapter#insert}.</li>
 *     <li>Commit DB transaction.</li>
 *     <li>Call {@link HibernateAccessStrategyAdapter#afterInsert}.</li>
 * </ul>
 * In case if some step fails and DB transaction is rolled back then
 * {@link HibernateAccessStrategyAdapter#afterInsert} is not called.
 * <p>
 * Update:
 * <ul>
 *     <li>Start DB transaction.</li>
 *     <li>Call {@link HibernateAccessStrategyAdapter#lock}.</li>
 *     <li>Execute database update.</li>
 *     <li>Call {@link HibernateAccessStrategyAdapter#update}.</li>
 *     <li>Commit DB transaction.</li>
 *     <li>Call {@link HibernateAccessStrategyAdapter#afterUpdate}.</li>
 * </ul>
 * In case if {@link HibernateAccessStrategyAdapter#lock} was called, but some other step fails and DB
 * transaction is rolled back then {@link HibernateAccessStrategyAdapter#unlock} is called for all locked keys.
 * <p>
 * Delete:
 * <ul>
 *     <li>Start DB transaction.</li>
 *     <li>Call {@link HibernateAccessStrategyAdapter#lock} for removing key.</li>
 *     <li>Execute database delete.</li>
 *     <li>Call {@link HibernateAccessStrategyAdapter#remove}.</li>
 *     <li>Commit DB transaction.</li>
 *     <li>Call {@link HibernateAccessStrategyAdapter#unlock}.</li>
 * </ul>
 * In case if {@link HibernateAccessStrategyAdapter#lock} was called, but some other step fails and DB
 * transaction is rolled back then {@link HibernateAccessStrategyAdapter#unlock} is called for all locked keys.
 * <p>
 * In case if custom SQL update query is executed Hibernate clears entire cache region,
 * for this case operations sequence is:
 * <ul>
 *     <li>Start DB transaction.</li>
 *     <li>Call {@link HibernateAccessStrategyAdapter#lockRegion}.</li>
 *     <li>Execute database query.</li>
 *     <li>Call {@link HibernateAccessStrategyAdapter#removeAll}.</li>
 *     <li>Commit DB transaction.</li>
 *     <li>Call {@link HibernateAccessStrategyAdapter#unlockRegion}.</li>
 * </ul>
 */
public abstract class HibernateAccessStrategyAdapter {
    /** */
    protected final HibernateCacheProxy cache;

    /** Grid. */
    protected final Ignite ignite;

    /** */
    protected final IgniteLogger log;

    /**
     * @param ignite Grid.
     * @param cache Cache.
     */
    protected HibernateAccessStrategyAdapter(Ignite ignite, HibernateCacheProxy cache) {
        this.cache = cache;
        this.ignite = ignite;

        log = ignite.log();
    }

    /**
     * @param key Key.
     * @return Cached value.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable protected Object get(Object key) throws IgniteCheckedException {
        return cache.get(key);
    }

    /**
     * @param key Key.
     * @param val Value.
     * @param minimalPutOverride MinimalPut flag
     * @throws IgniteCheckedException If failed.
     */
    protected void putFromLoad(Object key, Object val, boolean minimalPutOverride) throws IgniteCheckedException {
        putFromLoad(key, val);
    }

    /**
     * Puts in cache value loaded from the database.
     *
     * @param key Key.
     * @param val Value.
     * @throws IgniteCheckedException If failed.
     */
    protected void putFromLoad(Object key, Object val) throws IgniteCheckedException {
        cache.put(key, val);
    }

    /**
     * Called during database transaction execution before Hibernate attempts to update or remove given key.
     *
     * @param key Key.
     */
    protected abstract void lock(Object key);

    /**
     * Called after Hibernate failed to update or successfully removed given key.
     *
     * @param key Key.
     * @throws IgniteCheckedException If failed.
     */
    protected abstract void unlock(Object key) throws IgniteCheckedException;

    /**
     * Called after Hibernate updated object in the database but before transaction completed.
     *
     * @param key Key.
     * @param val Value.
     * @return {@code True} if operation updated cache.
     * @throws IgniteCheckedException If failed.
     */
    protected abstract boolean update(Object key, Object val) throws IgniteCheckedException;

    /**
     * Called after Hibernate updated object in the database and transaction successfully completed.
     *
     * @param key Key.
     * @param val Value.
     * @return {@code True} if operation updated cache.
     * @throws IgniteCheckedException If failed.
     */
    protected abstract boolean afterUpdate(Object key, Object val) throws IgniteCheckedException;

    /**
     * Called after Hibernate inserted object in the database but before transaction completed.
     *
     * @param key Key.
     * @param val Value.
     * @return {@code True} if operation updated cache.
     * @throws IgniteCheckedException If failed.
     */
    protected abstract boolean insert(Object key, Object val) throws IgniteCheckedException;

    /**
     * Called after Hibernate inserted object in the database and transaction successfully completed.
     *
     * @param key Key.
     * @param val Value.
     * @return {@code True} if operation updated cache.
     * @throws IgniteCheckedException If failed.
     */
    protected abstract boolean afterInsert(Object key, Object val) throws IgniteCheckedException;

    /**
     * Called after Hibernate removed object from database but before transaction completed.
     *
     * @param key Key,
     * @throws IgniteCheckedException If failed.
     */
    protected abstract void remove(Object key) throws IgniteCheckedException;

    /**
     * Called to remove object from cache without regard to transaction.
     *
     * @param key Key.
     * @throws IgniteCheckedException If failed.
     */
    protected void evict(Object key) throws IgniteCheckedException {
        evict(ignite, cache, key);
    }

    /**
     * Called to remove all data from cache without regard to transaction.
     *
     * @throws IgniteCheckedException If failed.
     */
    protected void evictAll() throws IgniteCheckedException {
        evictAll(cache);
    }

    /**
     * Called during database transaction execution to clear entire cache region after
     * Hibernate executed database update, but before transaction completed.
     *
     * @throws IgniteCheckedException If failed.
     */
    protected final void removeAll() throws IgniteCheckedException {
        evictAll();
    }

    /**
     * Called during database transaction execution before Hibernate executed
     * update operation which should invalidate entire cache region.
     */
    protected void lockRegion() {
        // No-op.
    }

    /**
     * Called after transaction clearing entire cache region completed.
     */
    protected void unlockRegion() {
        // No-op.
    }

    /**
     * Called to remove object from cache without regard to transaction.
     *
     * @param ignite Grid.
     * @param cache Cache.
     * @param key Key.
     * @throws IgniteCheckedException If failed.
     */
    static void evict(Ignite ignite, HibernateCacheProxy cache, Object key) throws IgniteCheckedException {
        try {
            key = cache.keyTransformer().transform(key);

            ignite.compute(ignite.cluster()).call(new ClearKeyCallable(key, cache.name()));
        }
        catch (IgniteException e) {
            throw new IgniteCheckedException(e);
        }
    }

    /**
     * Called to remove all data from cache without regard to transaction.
     *
     * @param cache Cache.
     * @throws IgniteCheckedException If failed.
     */
    static void evictAll(IgniteInternalCache<Object,Object> cache) throws IgniteCheckedException {
        cache.clear();
    }

    /**
     * Callable invalidates given key.
     */
    private static class ClearKeyCallable implements IgniteCallable<Void>, Externalizable {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        @IgniteInstanceResource
        private Ignite ignite;

        /** */
        private Object key;

        /** */
        private String cacheName;

        /**
         * Empty constructor required by {@link Externalizable}.
         */
        public ClearKeyCallable() {
            // No-op.
        }

        /**
         * @param key Key to clear.
         * @param cacheName Cache name.
         */
        private ClearKeyCallable(Object key, String cacheName) {
            this.key = key;
            this.cacheName = cacheName;
        }

        /** {@inheritDoc} */
        @Override public Void call() throws IgniteCheckedException {
            IgniteInternalCache<Object, Object> cache = ((IgniteKernal)ignite).getCache(cacheName);

            assert cache != null;

            cache.clearLocally(key);

            return null;
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(key);

            U.writeString(out, cacheName);
        }

        /** {@inheritDoc} */
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            key = in.readObject();

            cacheName = U.readString(in);
        }
    }
}