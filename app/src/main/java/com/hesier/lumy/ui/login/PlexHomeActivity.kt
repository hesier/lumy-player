package com.hesier.lumy.ui.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.core.HttpStatusException
import com.hesier.lumy.databinding.ActivityPlexHomeBinding
import com.hesier.lumy.databinding.ItemPlexHomeUserBinding
import com.hesier.lumy.plex.PlexHomeUser
import com.hesier.lumy.ui.ThemedActivity
import com.hesier.lumy.ui.servers.ServersActivity
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.scaleOnFocus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlexHomeActivity : ThemedActivity() {
    private lateinit var binding: ActivityPlexHomeBinding
    private val app get() = LumyApp.instance
    private val token get() = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
    private val replaceId get() = intent.getStringExtra(EXTRA_REPLACE_ID)
    private val preferredId get() = intent.getStringExtra(EXTRA_PREFERRED_ID)
    private var job: Job? = null
    private var pinPanel: PlexHomePinPanel? = null
    private var users = emptyList<PlexHomeUser>()
    private var selectedPosition = 0
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlexHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.users.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.users.preferredPosition = { selectedPosition }
        binding.users.keepVerticalFocus = true
        binding.users.itemAnimator = null
    }

    override fun onStart() {
        super.onStart()
        if (binding.retry.visibility == View.VISIBLE) binding.retry.requestFocus()
        else if (users.isEmpty() && job?.isActive != true) loadUsers() else focusSelected()
    }

    private fun loadUsers() {
        job?.cancel()
        setBusy(true)
        binding.retry.visibility = View.GONE
        binding.users.keepVerticalFocus = true
        binding.status.text = ""
        job = lifecycleScope.launch {
            val result = attempt { app.plexAccount.homeUsers(token) }
            setBusy(false)
            result.fold(onSuccess = ::showUsers, onFailure = ::showLoadError)
        }
    }

    private fun showUsers(members: List<PlexHomeUser>) {
        users = members
        if (members.isEmpty()) {
            showRetry(R.string.plex_home_empty) { loadUsers() }
            return
        }
        val currentId = app.store.servers.firstOrNull { it.id == replaceId }?.plexHome?.userId
        selectedPosition = members.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        binding.users.adapter = UserAdapter(currentId)
        binding.users.scrollToPosition(selectedPosition)
        focusSelected()
        if (members.size == 1 && !members.single().protected) switchUser(members.single())
    }

    private fun choose(user: PlexHomeUser) {
        if (busy || pinPanel?.isShowing == true) return
        selectedPosition = users.indexOf(user).coerceAtLeast(0)
        if (!user.protected) {
            switchUser(user)
            return
        }
        pinPanel = PlexHomePinPanel(this, user.title) { switchUser(user, it) }.apply {
            setOnDismissListener {
                job?.cancel()
                setBusy(false)
                binding.status.text = ""
                pinPanel = null
                focusSelected()
            }
            show()
        }
    }

    private fun switchUser(user: PlexHomeUser, pin: String? = null) {
        if (busy) return
        setBusy(true)
        binding.retry.visibility = View.GONE
        binding.status.setText(R.string.plex_home_switching)
        job = lifecycleScope.launch {
            val result = attempt { app.plexAccount.switchHomeUser(token, user.id, pin) }
            setBusy(false)
            result.fold(onSuccess = { activeToken -> openServers(user, activeToken) }, onFailure = { error ->
                val message = if (pin != null && error.isUnauthorized()) R.string.plex_home_pin_invalid
                    else if (error.isUnauthorized()) R.string.plex_home_auth_error else R.string.plex_home_network_error
                binding.status.setText(message)
                pinPanel?.showError(message)
                if (pinPanel == null) focusSelected()
                if (pin == null && error.isUnauthorized()) showLoadError(error)
            })
        }
    }

    private fun openServers(user: PlexHomeUser, activeToken: String) {
        // The picker remains on the back stack until a successful connection clears the task.
        pinPanel?.setOnDismissListener(null)
        pinPanel?.dismiss()
        pinPanel = null
        binding.status.text = ""
        startActivity(ServersActivity.intent(this, activeToken, autoConnect = true,
            preferredId = preferredId, replaceId = replaceId)
            .putExtra("plex_home_account_token", token)
            .putExtra("plex_home_user_id", user.id)
            .putExtra("plex_home_user_name", user.title)
            .putExtra("plex_home_user_thumb", user.thumb))
    }

    private fun showLoadError(error: Throwable) {
        if (error.isUnauthorized()) {
            showRetry(R.string.plex_home_auth_error, R.string.plex_home_login) {
                startActivity(LoginActivity.intent(this, preferredId = preferredId, replaceId = replaceId))
            }
        } else showRetry(R.string.plex_home_network_error) { loadUsers() }
    }

    private fun showRetry(message: Int, label: Int = R.string.plex_home_retry, action: () -> Unit) {
        binding.status.setText(message)
        binding.retry.setText(label)
        binding.retry.visibility = View.VISIBLE
        binding.users.keepVerticalFocus = false
        binding.retry.setOnClickListener { action() }
        binding.retry.requestFocus()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.progress.visibility = if (value) View.VISIBLE else View.INVISIBLE
    }

    private fun focusSelected() {
        binding.users.post {
            binding.users.findViewHolderForAdapterPosition(selectedPosition)?.itemView?.requestFocus()
        }
    }

    override fun onStop() {
        job?.cancel()
        pinPanel?.dismiss()
        setBusy(false)
        if (users.isNotEmpty() && binding.retry.visibility != View.VISIBLE) binding.status.text = ""
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (intent.getBooleanExtra(EXTRA_STARTUP, false)) finishAffinity() else super.onBackPressed()
    }

    private fun Throwable.isUnauthorized() = this is HttpStatusException && (code == 401 || code == 403)

    private inner class UserAdapter(private val currentId: String?) : RecyclerView.Adapter<UserAdapter.Holder>() {
        override fun getItemCount() = users.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemPlexHomeUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val user = users[position]
            holder.binding.apply {
                name.text = user.title
                lock.visibility = if (user.protected) View.VISIBLE else View.INVISIBLE
                current.visibility = if (user.id == currentId) View.VISIBLE else View.INVISIBLE
                avatar.load(user.thumb) {
                    placeholder(R.drawable.ic_person)
                    error(R.drawable.ic_person)
                    transformations(CircleCropTransformation())
                }
                root.setOnClickListener { choose(user) }
            }
        }

        inner class Holder(val binding: ItemPlexHomeUserBinding) : RecyclerView.ViewHolder(binding.root) {
            init {
                binding.root.scaleOnFocus()
                val scale = binding.root.onFocusChangeListener
                binding.root.setOnFocusChangeListener { view, focused ->
                    scale?.onFocusChange(view, focused)
                    if (focused && bindingAdapterPosition != RecyclerView.NO_POSITION) selectedPosition = bindingAdapterPosition
                }
            }
        }
    }

    companion object {
        private const val COLUMNS = 4
        private const val EXTRA_TOKEN = "account_token"
        private const val EXTRA_PREFERRED_ID = "preferred_id"
        private const val EXTRA_REPLACE_ID = "replace_id"
        private const val EXTRA_STARTUP = "startup"

        fun intent(context: Context, token: String, preferredId: String? = null,
            replaceId: String? = null, startup: Boolean = false): Intent =
            Intent(context, PlexHomeActivity::class.java)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_PREFERRED_ID, preferredId)
                .putExtra(EXTRA_REPLACE_ID, replaceId)
                .putExtra(EXTRA_STARTUP, startup)
    }
}
