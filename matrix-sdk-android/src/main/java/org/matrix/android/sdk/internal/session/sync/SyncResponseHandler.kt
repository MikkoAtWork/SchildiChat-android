/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.sync

import androidx.work.ExistingPeriodicWorkPolicy
import com.zhuinden.monarchy.Monarchy
import org.matrix.android.sdk.api.pushrules.PushRuleService
import org.matrix.android.sdk.api.pushrules.RuleScope
import org.matrix.android.sdk.api.session.initsync.InitSyncStep
import org.matrix.android.sdk.api.session.sync.model.GroupsSyncResponse
import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.api.session.sync.model.SyncResponse
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.crypto.DefaultCryptoService
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.di.WorkManagerProvider
import org.matrix.android.sdk.internal.session.SessionListeners
import org.matrix.android.sdk.internal.session.dispatchTo
import org.matrix.android.sdk.internal.session.group.GetGroupDataWorker
import org.matrix.android.sdk.internal.session.initsync.ProgressReporter
import org.matrix.android.sdk.internal.session.initsync.reportSubtask
import org.matrix.android.sdk.internal.session.notification.ProcessEventForPushTask
import org.matrix.android.sdk.internal.session.sync.handler.CryptoSyncHandler
import org.matrix.android.sdk.internal.session.sync.handler.GroupSyncHandler
import org.matrix.android.sdk.internal.session.sync.handler.PresenceSyncHandler
import org.matrix.android.sdk.internal.session.sync.handler.SyncResponsePostTreatmentAggregatorHandler
import org.matrix.android.sdk.internal.session.sync.handler.UserAccountDataSyncHandler
import org.matrix.android.sdk.internal.session.sync.handler.room.RoomSyncHandler
import org.matrix.android.sdk.internal.session.sync.handler.room.ThreadsAwarenessHandler
import org.matrix.android.sdk.internal.util.awaitTransaction
import org.matrix.android.sdk.internal.worker.WorkerParamsFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.system.measureTimeMillis

private const val GET_GROUP_DATA_WORKER = "GET_GROUP_DATA_WORKER"

internal class SyncResponseHandler @Inject constructor(
        @SessionDatabase private val monarchy: Monarchy,
        @SessionId private val sessionId: String,
        private val sessionManager: SessionManager,
        private val sessionListeners: SessionListeners,
        private val workManagerProvider: WorkManagerProvider,
        private val roomSyncHandler: RoomSyncHandler,
        private val userAccountDataSyncHandler: UserAccountDataSyncHandler,
        private val groupSyncHandler: GroupSyncHandler,
        private val cryptoSyncHandler: CryptoSyncHandler,
        private val aggregatorHandler: SyncResponsePostTreatmentAggregatorHandler,
        private val cryptoService: DefaultCryptoService,
        private val tokenStore: SyncTokenStore,
        private val processEventForPushTask: ProcessEventForPushTask,
        private val pushRuleService: PushRuleService,
        private val threadsAwarenessHandler: ThreadsAwarenessHandler,
        private val presenceSyncHandler: PresenceSyncHandler
) {

    suspend fun handleResponse(syncResponse: SyncResponse,
                               fromToken: String?,
                               reporter: ProgressReporter?) {
        val isInitialSync = fromToken == null
        Timber.v("Start handling sync, is InitialSync: $isInitialSync")
        android.util.Log.i("SCSCSC-SYNC", "Start handling sync, is InitialSync: $isInitialSync")

        measureTimeMillis {
            android.util.Log.i("SCSCSC-SYNC", "check crypto-service started")
            if (!cryptoService.isStarted()) {
                Timber.v("Should start cryptoService")
                android.util.Log.i("SCSCSC-SYNC", "Should start cryptoService")
                cryptoService.start()
                android.util.Log.i("SCSCSC-SYNC", "started cryptoService")
            }
            android.util.Log.i("SCSCSC-SYNC", "pre-onSyncWillProcess")
            cryptoService.onSyncWillProcess(isInitialSync)
            android.util.Log.i("SCSCSC-SYNC", "post-onSyncWillProcess")
        }.also {
            Timber.v("Finish handling start cryptoService in $it ms")
            android.util.Log.i("SCSCSC-SYNC", "Finish handling start cryptoService in $it ms")
        }

        // Handle the to device events before the room ones
        // to ensure to decrypt them properly
        measureTimeMillis {
            Timber.v("Handle toDevice")
            android.util.Log.i("SCSCSC-SYNC", "Handle toDevice")
            reportSubtask(reporter, InitSyncStep.ImportingAccountCrypto, 100, 0.1f) {
                android.util.Log.i("SCSCSC-SYNC", "Handle toDevice -01")
                if (syncResponse.toDevice != null) {
                    android.util.Log.i("SCSCSC-SYNC", "Handle toDevice -02")
                    cryptoSyncHandler.handleToDevice(syncResponse.toDevice, reporter)
                    android.util.Log.i("SCSCSC-SYNC", "Handle toDevice -03")
                }
                android.util.Log.i("SCSCSC-SYNC", "Handle toDevice -04")
            }
        }.also {
            Timber.v("Finish handling toDevice in $it ms")
            android.util.Log.i("SCSCSC-SYNC", "Finish handling toDevice in $it ms")
        }
        android.util.Log.i("SCSCSC-SYNC", "pre-aggregator")
        val aggregator = SyncResponsePostTreatmentAggregator()
        android.util.Log.i("SCSCSC-SYNC", "post-aggregator | pre fetchRoot")

        // Prerequisite for thread events handling in RoomSyncHandler
        threadsAwarenessHandler.fetchRootThreadEventsIfNeeded(syncResponse)
        android.util.Log.i("SCSCSC-SYNC", "post-fetchRootHreaed | pre big transaction")

        // Start one big transaction
        monarchy.awaitTransaction { realm ->
            measureTimeMillis {
                Timber.v("Handle rooms")
                android.util.Log.i("SCSCSC-SYNC", "Handle rooms")
                reportSubtask(reporter, InitSyncStep.ImportingAccountRoom, 1, 0.7f) {
                    android.util.Log.i("SCSCSC-SYNC", "Handle rooms -01")
                    if (syncResponse.rooms != null) {
                        android.util.Log.i("SCSCSC-SYNC", "Handle rooms -02")
                        roomSyncHandler.handle(realm, syncResponse.rooms, isInitialSync, aggregator, reporter)
                        android.util.Log.i("SCSCSC-SYNC", "Handle rooms -03")
                    }
                    android.util.Log.i("SCSCSC-SYNC", "Handle rooms -04")
                }
            }.also {
                Timber.v("Finish handling rooms in $it ms")
                android.util.Log.i("SCSCSC-SYNC", "Finish handling rooms in $it ms")
            }

            measureTimeMillis {
                android.util.Log.i("SCSCSC-SYNC", "pre-Handle groups")
                reportSubtask(reporter, InitSyncStep.ImportingAccountGroups, 1, 0.1f) {
                    Timber.v("Handle groups")
                    android.util.Log.i("SCSCSC-SYNC", "Handle groups")
                    if (syncResponse.groups != null) {
                        android.util.Log.i("SCSCSC-SYNC", "Handle groups -01")
                        groupSyncHandler.handle(realm, syncResponse.groups, reporter)
                        android.util.Log.i("SCSCSC-SYNC", "Handle groups -02")
                    }
                    android.util.Log.i("SCSCSC-SYNC", "Handle groups -03")
                }
            }.also {
                Timber.v("Finish handling groups in $it ms")
                android.util.Log.i("SCSCSC-SYNC", "Finish handling groups in $it ms")
            }

            measureTimeMillis {
                android.util.Log.i("SCSCSC-SYNC", "pre-accountData")
                reportSubtask(reporter, InitSyncStep.ImportingAccountData, 1, 0.1f) {
                    Timber.v("Handle accountData")
                    android.util.Log.i("SCSCSC-SYNC", "Handle accountData -01")
                    userAccountDataSyncHandler.handle(realm, syncResponse.accountData)
                    android.util.Log.i("SCSCSC-SYNC", "Handle accountData -02")
                }
            }.also {
                Timber.v("Finish handling accountData in $it ms")
                android.util.Log.i("SCSCSC-SYNC", "Finish handling accountData in $it ms")
            }

            measureTimeMillis {
                Timber.v("Handle Presence")
                android.util.Log.i("SCSCSC-SYNC", "presence -01")
                presenceSyncHandler.handle(realm, syncResponse.presence)
                android.util.Log.i("SCSCSC-SYNC", "presence -02")
            }.also {
                Timber.v("Finish handling Presence in $it ms")
                android.util.Log.i("SCSCSC-SYNC", "Finish handling Presence in $it ms")
            }
            android.util.Log.i("SCSCSC-SYNC", "pre sync-save-token")
            tokenStore.saveToken(realm, syncResponse.nextBatch)
            android.util.Log.i("SCSCSC-SYNC", "post sync-save-token")
        }

        // Everything else we need to do outside the transaction
        android.util.Log.i("SCSCSC-SYNC", "pre-aggregator-handle")
        aggregatorHandler.handle(aggregator)
        android.util.Log.i("SCSCSC-SYNC", "post-aggregator-handle")

        syncResponse.rooms?.let {
            android.util.Log.i("SCSCSC-SYNC", "check-push -01")
            checkPushRules(it, isInitialSync)
            android.util.Log.i("SCSCSC-SYNC", "check-push -02 / sync invite with server")
            userAccountDataSyncHandler.synchronizeWithServerIfNeeded(it.invite)
            android.util.Log.i("SCSCSC-SYNC", "check-push -03 / synced invite with server")
            dispatchInvitedRoom(it)
            android.util.Log.i("SCSCSC-SYNC", "check-push -04 / dispatced invite")
        }
        android.util.Log.i("SCSCSC-SYNC", "sync response rooms handled")
        syncResponse.groups?.let {
            android.util.Log.i("SCSCSC-SYNC", "pre-schedule group data fetching")
            scheduleGroupDataFetchingIfNeeded(it)
            android.util.Log.i("SCSCSC-SYNC", "post-schedule group data fetching")
        }

        Timber.v("On sync completed")
        android.util.Log.i("SCSCSC-SYNC", "On sync completed")
        cryptoSyncHandler.onSyncCompleted(syncResponse)
        android.util.Log.i("SCSCSC-SYNC", "On sync completed | cryptohandler knows")

        // post sync stuffs
        monarchy.writeAsync {
            android.util.Log.i("SCSCSC-SYNC", "pre-write-monarchy")
            roomSyncHandler.postSyncSpaceHierarchyHandle(it)
            android.util.Log.i("SCSCSC-SYNC", "post-write-monarchy")
        }
        android.util.Log.i("SCSCSC-SYNC", "finally done")
    }

    private fun dispatchInvitedRoom(roomsSyncResponse: RoomsSyncResponse) {
        val session = sessionManager.getSessionComponent(sessionId)?.session()
        roomsSyncResponse.invite.keys.forEach { roomId ->
            session.dispatchTo(sessionListeners) { session, listener ->
                listener.onNewInvitedRoom(session, roomId)
            }
        }
    }

    /**
     * At the moment we don't get any group data through the sync, so we poll where every hour.
     * You can also force to refetch group data using [Group] API.
     */
    private fun scheduleGroupDataFetchingIfNeeded(groupsSyncResponse: GroupsSyncResponse) {
        val groupIds = ArrayList<String>()
        groupIds.addAll(groupsSyncResponse.join.keys)
        groupIds.addAll(groupsSyncResponse.invite.keys)
        if (groupIds.isEmpty()) {
            Timber.v("No new groups to fetch data for.")
            return
        }
        Timber.v("There are ${groupIds.size} new groups to fetch data for.")
        val getGroupDataWorkerParams = GetGroupDataWorker.Params(sessionId)
        val workData = WorkerParamsFactory.toData(getGroupDataWorkerParams)

        val getGroupWork = workManagerProvider.matrixPeriodicWorkRequestBuilder<GetGroupDataWorker>(1, TimeUnit.HOURS)
                .setInputData(workData)
                .setConstraints(WorkManagerProvider.workConstraints)
                .build()

        workManagerProvider.workManager
                .enqueueUniquePeriodicWork(GET_GROUP_DATA_WORKER, ExistingPeriodicWorkPolicy.REPLACE, getGroupWork)
    }

    private suspend fun checkPushRules(roomsSyncResponse: RoomsSyncResponse, isInitialSync: Boolean) {
        Timber.v("[PushRules] --> checkPushRules")
        if (isInitialSync) {
            Timber.v("[PushRules] <-- No push rule check on initial sync")
            return
        } // nothing on initial sync

        android.util.Log.e("SCSCSC-PURU", "getting push rules")
        val rules = pushRuleService.getPushRules(RuleScope.GLOBAL).getAllRules()
        android.util.Log.e("SCSCSC-PURU", "got push rules; execute")
        processEventForPushTask.execute(ProcessEventForPushTask.Params(roomsSyncResponse, rules))
        android.util.Log.e("SCSCSC-PURU", "scheduled push task")
        Timber.v("[PushRules] <-- Push task scheduled")
    }
}
