/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.snapshot;

import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.log4j.Logger;

import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.fsm.NoTransitionException;

public class CephSnapshotStrategy extends StorageSystemSnapshotStrategy {
    @Inject
    private SnapshotDataStoreDao snapshotStoreDao;
    @Inject
    private SnapshotDataFactory snapshotDataFactory;
    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VolumeDetailsDao volumeDetailsDao;
    @Inject
    private SnapshotDao snapshotDao;


    private static final Logger s_logger = Logger.getLogger(CephSnapshotStrategy.class);

    @Override
    public StrategyPriority canHandle(Snapshot snapshot, SnapshotOperation op) {
        long volumeId = snapshot.getVolumeId();
        VolumeVO volumeVO = volumeDao.findByIdIncludingRemoved(volumeId);
        boolean baseVolumeExists = volumeVO.getRemoved() == null;
        if (!baseVolumeExists) {
            return StrategyPriority.CANT_HANDLE;
        }

        if (!isSnapshotStoredOnRbdStoragePool(snapshot)) {
            return StrategyPriority.CANT_HANDLE;
        }

        if (SnapshotOperation.REVERT.equals(op)) {
            return StrategyPriority.HIGHEST;
        }

        if (SnapshotOperation.DELETE.equals(op)) {
            return StrategyPriority.HIGHEST;
        }

        return StrategyPriority.CANT_HANDLE;
    }

    @Override
    public boolean revertSnapshot(SnapshotInfo snapshotInfo) {
        VolumeInfo volumeInfo = snapshotInfo.getBaseVolume();
        ImageFormat imageFormat = volumeInfo.getFormat();
        if (!ImageFormat.RAW.equals(imageFormat)) {
            s_logger.error(String.format("Does not support revert snapshot of the image format [%s] on Ceph/RBD. Can only rollback snapshots of format RAW", imageFormat));
            return false;
        }
        executeRevertSnapshot(snapshotInfo, volumeInfo);
        return true;
    }

    /**
     * Returns true if the snapshot is stored on a RBD storage pool.
     */
    protected boolean isSnapshotStoredOnRbdStoragePool(Snapshot snapshot) {
        SnapshotDataStoreVO snapshotStore = snapshotStoreDao.findBySnapshot(snapshot.getId(), DataStoreRole.Primary);
        if (snapshotStore == null) {
            return false;
        }
        StoragePoolVO storagePoolVO = primaryDataStoreDao.findById(snapshotStore.getDataStoreId());
        return storagePoolVO != null && storagePoolVO.getPoolType() == StoragePoolType.RBD;
    }

    @Override
    public boolean deleteSnapshot(Long snapshotId) {
        SnapshotVO snapshotVO = snapshotDao.findById(snapshotId);

        if (snapshotVO.getState() == Snapshot.State.Allocated) {
            snapshotDao.remove(snapshotId);
            return true;
        }

        if (snapshotVO.getState() == Snapshot.State.Destroyed) {
            return true;
        }

        if (Snapshot.State.Error.equals(snapshotVO.getState())) {
            List<SnapshotDataStoreVO> storeRefs = snapshotStoreDao.findBySnapshotId(snapshotId);
            for (SnapshotDataStoreVO ref : storeRefs) {
                snapshotStoreDao.expunge(ref.getId());
            }
            snapshotDao.remove(snapshotId);
            return true;
        }

        boolean deleteSnapshotOnSecondaryStorage = deleteSnapshotOnSecondaryStorage(snapshotId);
        boolean deleteSnapshotOnPrimaryStorage = cleanupSnapshotOnPrimaryStore(snapshotId);

        if (deleteSnapshotOnSecondaryStorage || deleteSnapshotOnPrimaryStorage) {
            snapshotDao.remove(snapshotId);
            return true;
        }
        return false;
    }

    /**
     * Deletes snapshot on secondary storage.
     * It can return false when the snapshot was stored on primary storage and not backed up on secondary; therefore, the snapshot should also be deleted on primary storage even when this method returns false.
     */
    private boolean deleteSnapshotOnSecondaryStorage(Long snapshotId) {
        SnapshotInfo snapshotOnImage = snapshotDataFactory.getSnapshot(snapshotId, DataStoreRole.Image);
        if (snapshotOnImage == null) {
            s_logger.debug(String.format("Can't find snapshot (id: %d) on backup storage, delete it in db", snapshotId));
            return true;
        }
        SnapshotObject obj = (SnapshotObject)snapshotOnImage;
        try {
            obj.processEvent(Snapshot.Event.DestroyRequested);
        } catch (NoTransitionException e) {
            s_logger.debug("Failed to set the state to destroying: ", e);
        }
        boolean result = snapshotSvr.deleteSnapshot(snapshotOnImage);
        try {
            if (result) {
                obj.processEvent(Snapshot.Event.OperationSucceeded);
            } else {
                obj.processEvent(Snapshot.Event.OperationFailed);
            }
        } catch (NoTransitionException e) {
            s_logger.debug("Failed to set the state to destroying: ", e);
            return false;
        }
        return true;
    }
}