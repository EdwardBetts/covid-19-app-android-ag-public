package uk.nhs.nhsx.covid19.android.app.exposure.encounter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.observe
import kotlinx.android.synthetic.main.activity_exposed_notification.isolationDays
import kotlinx.android.synthetic.main.activity_exposed_notification.understandButton
import uk.nhs.nhsx.covid19.android.app.R
import uk.nhs.nhsx.covid19.android.app.R.plurals
import uk.nhs.nhsx.covid19.android.app.appComponent
import uk.nhs.nhsx.covid19.android.app.common.ViewModelFactory
import uk.nhs.nhsx.covid19.android.app.exposure.encounter.EncounterDetectionViewModel.ExposedNotificationResult.ConsentConfirmation
import uk.nhs.nhsx.covid19.android.app.exposure.encounter.EncounterDetectionViewModel.ExposedNotificationResult.IsolationDurationDays
import uk.nhs.nhsx.covid19.android.app.startActivity
import uk.nhs.nhsx.covid19.android.app.status.StatusActivity
import javax.inject.Inject

class EncounterDetectionActivity : AppCompatActivity(R.layout.activity_exposed_notification) {

    @Inject
    lateinit var factory: ViewModelFactory<EncounterDetectionViewModel>

    private val viewModel: EncounterDetectionViewModel by viewModels { factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appComponent.inject(this)

        viewModel.getIsolationDays()

        viewModel.isolationState().observe(this) { state ->
            when (state) {
                is IsolationDurationDays -> displayIsolationDays(state)
                ConsentConfirmation -> navigateToStatusScreen()
            }
        }

        understandButton.setOnClickListener {
            viewModel.confirmConsent()
        }
    }

    private fun navigateToStatusScreen() {
        startActivity<StatusActivity> {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    }

    private fun displayIsolationDays(state: IsolationDurationDays) {
        isolationDays.text = resources.getQuantityString(
            plurals.state_isolation_days,
            state.days,
            state.days
        )
    }

    companion object {

        fun start(context: Context) = context.startActivity(
            getIntent(context)
        )

        fun getIntent(context: Context) =
            Intent(context, EncounterDetectionActivity::class.java)
                .apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
    }
}
