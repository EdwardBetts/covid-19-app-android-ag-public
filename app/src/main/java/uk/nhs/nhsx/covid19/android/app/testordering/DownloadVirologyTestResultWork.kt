package uk.nhs.nhsx.covid19.android.app.testordering

import androidx.annotation.VisibleForTesting
import androidx.work.ListenableWorker
import com.jeroenmols.featureflag.framework.FeatureFlag
import com.jeroenmols.featureflag.framework.RuntimeBehavior
import timber.log.Timber
import uk.nhs.nhsx.covid19.android.app.analytics.AnalyticsEvent.NegativeResultReceived
import uk.nhs.nhsx.covid19.android.app.analytics.AnalyticsEvent.PositiveResultReceived
import uk.nhs.nhsx.covid19.android.app.analytics.AnalyticsEvent.VoidResultReceived
import uk.nhs.nhsx.covid19.android.app.analytics.AnalyticsEventProcessor
import uk.nhs.nhsx.covid19.android.app.remote.VirologyTestingApi
import uk.nhs.nhsx.covid19.android.app.remote.data.VirologyTestResult.NEGATIVE
import uk.nhs.nhsx.covid19.android.app.remote.data.VirologyTestResult.POSITIVE
import uk.nhs.nhsx.covid19.android.app.remote.data.VirologyTestResult.VOID
import uk.nhs.nhsx.covid19.android.app.remote.data.VirologyTestResultRequestBody
import uk.nhs.nhsx.covid19.android.app.remote.data.VirologyTestResultResponse
import uk.nhs.nhsx.covid19.android.app.state.IsolationConfigurationProvider
import uk.nhs.nhsx.covid19.android.app.state.IsolationStateMachine
import uk.nhs.nhsx.covid19.android.app.state.OnNegativeTestResult
import uk.nhs.nhsx.covid19.android.app.state.OnPositiveTestResult
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit.DAYS
import javax.inject.Inject

class DownloadVirologyTestResultWork(
    private val virologyTestingApi: VirologyTestingApi,
    private val testOrderingTokensProvider: TestOrderingTokensProvider,
    private val latestTestResultProvider: LatestTestResultProvider,
    private val stateMachine: IsolationStateMachine,
    private val isolationConfigurationProvider: IsolationConfigurationProvider,
    private val analyticsEventProcessor: AnalyticsEventProcessor,
    private val clock: Clock
) {

    @Inject
    constructor(
        virologyTestingApi: VirologyTestingApi,
        testOrderingTokensProvider: TestOrderingTokensProvider,
        latestTestResultProvider: LatestTestResultProvider,
        stateMachine: IsolationStateMachine,
        isolationConfigurationProvider: IsolationConfigurationProvider,
        analyticsEventProcessor: AnalyticsEventProcessor
    ) : this(
        virologyTestingApi,
        testOrderingTokensProvider,
        latestTestResultProvider,
        stateMachine,
        isolationConfigurationProvider,
        analyticsEventProcessor,
        clock = Clock.systemUTC()
    )

    suspend operator fun invoke(): ListenableWorker.Result {
        if (!RuntimeBehavior.isFeatureEnabled(FeatureFlag.TEST_ORDERING)) {
            return ListenableWorker.Result.success()
        }

        val configs = testOrderingTokensProvider.configs

        var latestTestResult: LatestTestResult? = null

        configs.forEach { config ->
            try {
                // FIXME: move into separate class
                if (removeIfOld(config)) {
                    return@forEach
                }

                val pollingToken = config.testResultPollingToken
                val virologyTestResult = fetchVirologyTestResult(pollingToken) ?: return@forEach
                if (latestTestResult == null || virologyTestResult.testEndDate.isAfter(
                    latestTestResult?.testEndDate
                )
                ) {
                    latestTestResult = LatestTestResult(
                        diagnosisKeySubmissionToken = config.diagnosisKeySubmissionToken,
                        testEndDate = virologyTestResult.testEndDate,
                        testResult = virologyTestResult.testResult
                    )
                }

                testOrderingTokensProvider.remove(config)
            } catch (exception: Exception) {
                Timber.e(exception)
            }
        }

        latestTestResultProvider.latestTestResult = latestTestResult

        latestTestResult?.let {
            when (it.testResult) {
                POSITIVE -> {
                    stateMachine.processEvent(OnPositiveTestResult(it.testEndDate))
                    analyticsEventProcessor.track(PositiveResultReceived)
                }
                NEGATIVE -> {
                    stateMachine.processEvent(OnNegativeTestResult(it.testEndDate))
                    analyticsEventProcessor.track(NegativeResultReceived)
                }
                VOID -> {
                    Timber.d("Latest test result is void")
                    analyticsEventProcessor.track(VoidResultReceived)
                }
            }
        }

        return ListenableWorker.Result.success()
    }

    private suspend fun fetchVirologyTestResult(pollingToken: String): VirologyTestResultResponse? {
        val testResultResponse =
            virologyTestingApi.getTestResult(VirologyTestResultRequestBody(pollingToken))

        if (testResultResponse.code() != 200) {
            Timber.e("Test result polling returned error status code ${testResultResponse.code()}")
        }

        return testResultResponse.body()
    }

    @VisibleForTesting
    fun removeIfOld(config: TestOrderPollingConfig): Boolean {
        val houseKeepingDeletionPeriod =
            isolationConfigurationProvider.durationDays.pendingTasksRetentionPeriod
        val configExpiryInstant = config.startedAt.plus(houseKeepingDeletionPeriod.toLong(), DAYS)
        val now = Instant.now(clock)
        if (now.isAfter(configExpiryInstant)) {
            testOrderingTokensProvider.remove(config)
            return true
        }
        return false
    }
}
