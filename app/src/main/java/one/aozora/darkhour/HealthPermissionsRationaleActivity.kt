package one.aozora.darkhour

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))),
            )
        }
        finish()
    }
}
