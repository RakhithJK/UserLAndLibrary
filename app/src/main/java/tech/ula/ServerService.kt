package tech.ula

import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.iiordanov.bVNC.Constants
import com.iiordanov.bVNC.RemoteCanvasActivity
import kotlinx.coroutines.*
import tech.ula.model.entities.App
import tech.ula.model.entities.ServiceType
import tech.ula.model.entities.Session
import tech.ula.model.repositories.UlaDatabase
import tech.ula.utils.*
import kotlin.coroutines.CoroutineContext

class ServerService : Service(), CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    companion object {
        const val SERVER_SERVICE_RESULT: String = "tech.ula.ServerService.RESULT"
    }

    private val activeSessions: MutableMap<Long, Session> = mutableMapOf()

    private lateinit var broadcaster: LocalBroadcastManager

    private val notificationManager: NotificationConstructor by lazy {
        NotificationConstructor(this)
    }

    private val busyboxExecutor by lazy {
        val ulaFiles = UlaFiles(this, this.applicationInfo.nativeLibraryDir)
        val prootDebugLogger = ProotDebugLogger(this.defaultSharedPreferences, ulaFiles)
        BusyboxExecutor(ulaFiles, prootDebugLogger)
    }

    private val localServerManager by lazy {
        LocalServerManager(this.filesDir.path, busyboxExecutor, this.defaultSharedPreferences)
    }

    override fun onCreate() {
        broadcaster = LocalBroadcastManager.getInstance(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.getStringExtra("type")) {
            "start" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                this.launch { startSession(session) }
            }
            "stopApp" -> {
                val app: App = intent.getParcelableExtra("app")!!
                stopApp(app)
            }
            "restartRunningSession" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                startClient(session)
            }
            "kill" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                killSession(session)
            }
            "filesystemIsBeingDeleted" -> {
                val filesystemId: Long = intent.getLongExtra("filesystemId", -1)
                cleanUpFilesystem(filesystemId)
            }
            "stopAll" -> {
                activeSessions.forEach { (_, session) ->
                    killSession(session)
                }
            }
        }

        return START_STICKY
    }

    // Used in conjunction with manifest attribute `android:stopWithTask="true"`
    // to clean up when app is swiped away.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Redundancy to ensure no hanging processes, given broad device spectrum.
        this.coroutineContext.cancel()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Redundancy to ensure no hanging processes, given broad device spectrum.
        this.coroutineContext.cancel()
    }

    private fun removeSession(session: Session) {
        activeSessions.remove(session.pid)
        if (activeSessions.isEmpty()) {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun updateSession(session: Session) = CoroutineScope(Dispatchers.Default).launch {
        UlaDatabase.getInstance(this@ServerService).sessionDao().updateSession(session)
    }

    private fun killSession(session: Session) {
        localServerManager.stopService(session)
        removeSession(session)
        session.active = false
        updateSession(session)
    }

    private suspend fun startSession(session: Session) {
        startForeground(NotificationConstructor.serviceNotificationId, notificationManager.buildPersistentServiceNotification())
        session.pid = localServerManager.startServer(session)

        while (!localServerManager.isServerRunning(session)) {
            delay(500)
        }

        session.active = true
        updateSession(session)
        startClient(session)
        activeSessions[session.pid] = session
    }

    private fun stopApp(app: App) {
        val appSessions = activeSessions.filter { (_, session) ->
            session.name == app.name
        }
        appSessions.forEach { (_, session) ->
            killSession(session)
        }
    }

    private fun startClient(session: Session) {
        when (session.serviceType) {
            ServiceType.Ssh -> startSshClient(session)
            ServiceType.Vnc -> startVncClient(session, "com.iiordanov.freebVNC")
            ServiceType.Xsdl -> startXsdlClient("x.org.server")
            else -> sendDialogBroadcast("unhandledSessionServiceType")
        }
        sendSessionActivatedBroadcast()
    }

    private fun startSshClient(session: Session) {
        val connectBotIntent = Intent()
        connectBotIntent.action = Intent.ACTION_VIEW
        connectBotIntent.data = Uri.parse("ssh://${session.username}@localhost:2022/#userland")
        connectBotIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        startActivity(connectBotIntent)
    }

    private fun startVncClient(session: Session, packageName: String) {
        val bVncIntent = Intent(this, RemoteCanvasActivity::class.java)
        bVncIntent.data = Uri.parse("vnc://127.0.0.1:5951/?VncUsername=${session.username}&VncPassword=${session.vncPassword}")
        bVncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        bVncIntent.putExtra("hide_toolbar", false)
        bVncIntent.putExtra("input_mode", "TOUCH_ZOOM_MODE")

        if (clientIsPresent(bVncIntent)) {
            this.startActivity(bVncIntent)
        } else {
            getClient(packageName)
        }
    }

    private fun startXsdlClient(packageName: String) {
        val xsdlIntent = Intent()
        xsdlIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        xsdlIntent.data = Uri.parse("x11://give.me.display:4721")

        if (clientIsPresent(xsdlIntent)) {
            startActivity(xsdlIntent)
        } else {
            getClient(packageName)
        }
    }

    private fun clientIsPresent(intent: Intent): Boolean {
        val activities = packageManager.queryIntentActivities(intent, 0)
        return (activities.size > 0)
    }

    private fun getClient(packageName: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            this.startActivity(intent)
        } catch (err: ActivityNotFoundException) {
            sendDialogBroadcast("playStoreMissingForClient")
        }
    }

    private fun cleanUpFilesystem(filesystemId: Long) {
        activeSessions.values.filter { it.filesystemId == filesystemId }
                .forEach { killSession(it) }
    }

    private fun sendSessionActivatedBroadcast() {
        val intent = Intent(SERVER_SERVICE_RESULT)
                .putExtra("type", "sessionActivated")
        broadcaster.sendBroadcast(intent)
    }

    private fun sendDialogBroadcast(type: String) {
        val intent = Intent(SERVER_SERVICE_RESULT)
                .putExtra("type", "dialog")
                .putExtra("dialogType", type)
        broadcaster.sendBroadcast(intent)
    }
}