package com.storyweaver.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.storyweaver.app.data.SafStorage
import com.storyweaver.app.ui.PrivacyLockScreen
import com.storyweaver.app.ui.authenticate
import com.storyweaver.app.ui.theme.StoryWeaverTheme
import com.storyweaver.core.model.ThemeMode
import com.storyweaver.core.model.UserSettings
import com.storyweaver.feature.create.CreateScreen
import com.storyweaver.feature.create.CreateViewModel
import com.storyweaver.feature.library.LibraryScreen
import com.storyweaver.feature.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedText = extractSharedText(intent)
        setContent {
            val app = application as StoryWeaverApp
            val settings by app.container.preferences.settings.collectAsStateWithLifecycle(
                initialValue = UserSettings(),
            )
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(settings.privacyMode) {
                if (settings.privacyMode) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            StoryWeaverTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                StoryWeaverRoot(
                    sharedText = sharedText,
                    settings = settings,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private data class Dest(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun StoryWeaverRoot(
    sharedText: String?,
    settings: UserSettings,
) {
    val app = LocalContext.current.applicationContext as StoryWeaverApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as FragmentActivity
    var unlocked by remember { mutableStateOf(!settings.privacyMode) }
    var showAgeGate by remember { mutableStateOf(!settings.ageAcknowledged) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var cacheSummary by remember { mutableStateOf("—") }
    var saveFolderMessage by remember { mutableStateOf<String?>(null) }
    val collections by app.container.collections.observeCollections()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val exportHistory by app.container.exportHistory.observe()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        SafStorage.takePersistableReadWrite(context, uri)
        scope.launch {
            app.container.preferences.update {
                it.copy(defaultOutputFolderUri = uri.toString())
            }
        }
    }

    LaunchedEffect(settings.privacyMode) {
        if (!settings.privacyMode) unlocked = true
    }

    LaunchedEffect(Unit) {
        refreshCacheSummary(app) { cacheSummary = it }
    }

    if (showAgeGate) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Personal use notice") },
            text = {
                Text(
                    "You must be 18 or older. StoryWeaver fetches public Literotica pages and " +
                        "builds DRM-free EPUBs entirely on your device. It is not affiliated with Literotica. " +
                        "Use only content you are allowed to access for personal use.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        app.container.preferences.update { it.copy(ageAcknowledged = true) }
                        showAgeGate = false
                    }
                }) { Text("I am 18+ and accept") }
            },
        )
    }

    if (settings.privacyMode && !unlocked && !showAgeGate) {
        PrivacyLockScreen(
            onUnlock = {
                authenticate(activity) { ok -> if (ok) unlocked = true }
            },
        )
        return
    }

    val navController = rememberNavController()
    val destinations = listOf(
        Dest("create", "Create", Icons.Default.Create),
        Dest("library", "Library", Icons.AutoMirrored.Filled.LibraryBooks),
        Dest("settings", "Settings", Icons.Default.Settings),
    )

    val createVm: CreateViewModel = viewModel(
        factory = CreateViewModel.factory(
            source = app.container.literoticaSource,
            epubWriter = app.container.epubWriter,
            epubImporter = app.container.epubImporter,
            epubMerger = app.container.epubMerger,
            collections = app.container.collectionStore,
            chapterCache = app.container.chapterCachePort,
            exportHistory = app.container.exportHistoryPort,
            concurrency = settings.downloadConcurrency,
            style = settings.epubStyle,
        ),
    )

    if (sharedText != null) {
        LaunchedEffect(sharedText) {
            createVm.onUrlChange(sharedText)
        }
    }

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val current = navBackStackEntry?.destination
            NavigationBar {
                destinations.forEach { dest ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == dest.route } == true,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "create",
            modifier = Modifier.padding(padding),
        ) {
            composable("create") {
                CreateScreen(
                    viewModel = createVm,
                    defaultOutputFolderUri = settings.defaultOutputFolderUri,
                    onSaveToFolder = { filename, bytes ->
                        val folder = settings.defaultOutputFolderUri ?: return@CreateScreen null
                        val uri = withContext(Dispatchers.IO) {
                            SafStorage.writeEpub(
                                context = context,
                                treeUri = Uri.parse(folder),
                                preferredFilename = filename,
                                bytes = bytes,
                            )
                        }
                        saveFolderMessage = if (uri != null) "Saved $filename" else "Could not save to folder"
                        uri
                    },
                )
            }
            composable("library") {
                LibraryScreen(
                    collections = collections,
                    exportHistory = exportHistory,
                    onOpen = { saved ->
                        createVm.loadSaved(
                            draft = saved.draft,
                            authors = saved.authors,
                            works = saved.works,
                            chapters = saved.chapters,
                            sourceUrls = saved.sourceUrls,
                        )
                        navController.navigate("create")
                    },
                    onDelete = { saved ->
                        scope.launch {
                            app.container.collections.delete(saved.draft.id)
                        }
                    },
                    onRefresh = { saved ->
                        createVm.loadSaved(
                            draft = saved.draft,
                            authors = saved.authors,
                            works = saved.works,
                            chapters = saved.chapters,
                            sourceUrls = saved.sourceUrls,
                        )
                        if (saved.sourceUrls.isNotEmpty()) {
                            createVm.onUrlChange(saved.sourceUrls.joinToString("\n"))
                            createVm.analyze()
                        }
                        navController.navigate("create")
                    },
                )
            }
            composable("settings") {
                SettingsScreen(
                    settings = settings,
                    cacheSummary = cacheSummary,
                    outputFolderLabel = SafStorage.displayName(context, settings.defaultOutputFolderUri),
                    updateMessage = updateMessage,
                    onThemeChange = { mode ->
                        scope.launch { app.container.preferences.update { it.copy(themeMode = mode) } }
                    },
                    onDynamicColorChange = { enabled ->
                        scope.launch { app.container.preferences.update { it.copy(dynamicColor = enabled) } }
                    },
                    onConcurrencyChange = { n ->
                        scope.launch {
                            app.container.preferences.update { it.copy(downloadConcurrency = n) }
                            createVm.setConcurrency(n)
                        }
                    },
                    onStyleChange = { style ->
                        scope.launch {
                            app.container.preferences.update { it.copy(epubStyle = style) }
                            createVm.onStyleChange(style)
                        }
                    },
                    onPrivacyChange = { enabled ->
                        scope.launch {
                            app.container.preferences.update { it.copy(privacyMode = enabled) }
                            if (enabled) unlocked = false
                        }
                    },
                    onPickOutputFolder = { pickFolder.launch(null) },
                    onClearOutputFolder = {
                        scope.launch {
                            app.container.preferences.update { it.copy(defaultOutputFolderUri = null) }
                        }
                    },
                    onClearCache = {
                        scope.launch {
                            app.container.chapterCache.clearAll()
                            refreshCacheSummary(app) { cacheSummary = it }
                        }
                    },
                    onCheckUpdates = {
                        scope.launch {
                            updateMessage = "Checking…"
                            updateMessage = app.container.appUpdateChecker.check()
                        }
                    },
                )
            }
        }
    }
}

private suspend fun refreshCacheSummary(app: StoryWeaverApp, set: (String) -> Unit) {
    val (count, bytes) = withContext(Dispatchers.IO) { app.container.chapterCache.stats() }
    val kb = bytes / 1024
    set("$count cached chapters · ~${kb} KB")
}
