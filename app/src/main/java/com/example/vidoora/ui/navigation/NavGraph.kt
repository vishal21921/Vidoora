package com.example.vidoora.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import kotlinx.coroutines.launch
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.vidoora.core.network.SharedVideoLink
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.vidoora.feature.auth.AuthViewModel
import com.example.vidoora.feature.auth.login.LoginScreen
import com.example.vidoora.feature.auth.register.RegisterScreen
import com.example.vidoora.feature.home.HomeScreen
import com.example.vidoora.feature.home.HomeViewModel
import com.example.vidoora.feature.profile.ProfileScreen
import com.example.vidoora.feature.profile.ProfileViewModel
import com.example.vidoora.feature.splash.SplashScreen
import com.example.vidoora.feature.splash.SplashViewModel
import com.example.vidoora.feature.upload.UploadFormScreen
import com.example.vidoora.feature.upload.UploadQueueScreen
import com.example.vidoora.feature.upload.UploadViewModel
import com.example.vidoora.feature.playback.*
import com.example.vidoora.feature.ads.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.lifecycle.repeatOnLifecycle

object NavRoutes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val FORGOT_PASSWORD = "forgot_password"
    const val REGISTER = "register"
    const val HOME = "home"
    const val PROFILE = "profile"
    const val PLANS = "membership_plans"
    const val UPLOAD = "upload"
    const val NEW_UPLOAD = "new_upload"
    const val HISTORY = "history"
    const val PAYMENTS = "payments"
    const val DOWNLOADS = "downloads"
    const val SUPPORT = "support"
    const val SETTINGS = "settings"
    const val UPLOAD_QUEUE = "upload_queue"
    const val FOLDERS = "folders"
    const val VIDEOS = "videos"
    const val PLAYER = "player/{videoId}"
    const val DETAILS = "details/{videoId}?viewer={viewer}"
    const val BLOCKED = "blocked_uploaders"
    const val OPEN_LINK = "open_link"
}

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
    incomingLink: SharedVideoLink? = null,
    onIncomingHandled: () -> Unit = {}
) {
    val snackbar=remember { SnackbarHostState() }
    val scope=rememberCoroutineScope()
    val navigation: VideoNavigationViewModel = hiltViewModel()
    val session: HomeViewModel = hiltViewModel()
    val currentUser by session.currentUser.collectAsState()
    val signedIn=currentUser!=null
    val offline: com.example.vidoora.feature.downloads.DownloadsViewModel=hiltViewModel()
    LaunchedEffect(currentUser?.id){if(currentUser!=null) offline.repository.recover()}
    val adModel: ViewerAdsViewModel = hiltViewModel()
    val ads=adModel.ads
    val membershipModel: com.example.vidoora.feature.membership.MembershipViewModel = hiltViewModel()
    val membership=membershipModel.membership
    val observedMember by membership.state.collectAsState()
    // Account changes may precede the next one-second lease tick; render only current-session access.
    val memberState=membership.current()
    val shellLifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val creatorAccess = currentUser?.canUpload == true
    LaunchedEffect(shellLifecycle, currentUser?.id, creatorAccess) {
        if (currentUser != null) shellLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            do {
                session.refreshProfile()
                if (creatorAccess) kotlinx.coroutines.delay(15_000)
            } while (creatorAccess)
        }
    }
    LaunchedEffect(shellLifecycle,membership) {
        shellLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            launch { while(true) { membership.tick(); kotlinx.coroutines.delay(1_000) } }
            launch { while(true) { membership.refresh(); kotlinx.coroutines.delay(5_000) } }
        }
    }
    LaunchedEffect(observedMember,currentUser?.id) { ads.membershipChanged() }
    var paymentReference by remember(currentUser?.id) { mutableStateOf("") }
    val appContext=LocalContext.current
    LaunchedEffect(currentUser?.id) { if(currentUser!=null) com.example.vidoora.feature.membership.ReceiptRecoveryWorker.enqueue(appContext) }
    var afterLogin by rememberSaveable { mutableStateOf(NavRoutes.PROFILE) }
    fun logInFor(destination: String) {
        afterLogin=destination
        navController.navigate(NavRoutes.LOGIN) { launchSingleTop=true }
    }
    fun openLink(link: SharedVideoLink) {
        navigation.shareAccess.remember(link)
        navController.navigate("details/${link.videoId}?viewer=true") { launchSingleTop=true }
    }
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(incomingLink,currentEntry?.destination?.route) {
        if(incomingLink!=null && currentEntry!=null && currentEntry?.destination?.route !in setOf(NavRoutes.SPLASH,NavRoutes.LOGIN,NavRoutes.REGISTER)) {
            navigation.shareAccess.remember(incomingLink)
            navController.navigate("details/${incomingLink.videoId}?viewer=true") { launchSingleTop=true }
            onIncomingHandled()
        }
    }
    val route=currentEntry?.destination?.route
    Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),snackbarHost={ SnackbarHost(snackbar) },bottomBar={
        if(route in setOf(NavRoutes.HOME,NavRoutes.HISTORY,NavRoutes.UPLOAD,NavRoutes.PROFILE)) {
            ReferenceBottomBar(route,currentUser?.canUpload==true) { destination ->
                if(route!=destination) navController.navigate(destination) {
                    popUpTo(NavRoutes.HOME) { inclusive=false }
                    launchSingleTop=true
                }
            }
        }
    }) { shellPadding ->
    NavHost(
        navController = navController,
        modifier = Modifier.padding(shellPadding).consumeWindowInsets(shellPadding),
        startDestination = NavRoutes.SPLASH
    ) {
        composable(NavRoutes.SPLASH) {
            val viewModel: SplashViewModel = hiltViewModel()
            val state by viewModel.splashState.collectAsState()
            SplashScreen(
                state = state,
                onAuthenticated = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.SPLASH) { inclusive = true }
                    }
                },
                onUnauthenticated = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.FORGOT_PASSWORD) {com.example.vidoora.feature.auth.PasswordResetScreen({navController.popBackStack()})}
        composable(NavRoutes.LOGIN) {
            val viewModel: AuthViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()
            LoginScreen(
                uiState = uiState,
                onForgotPassword = {navController.navigate(NavRoutes.FORGOT_PASSWORD)},
                onLoginClick = { email, pass -> viewModel.login(email, pass) },
                onNavigateToRegister = {
                    viewModel.clearState()
                    navController.navigate(NavRoutes.REGISTER)
                },
                onLoginSuccess = {
                    viewModel.clearState()
                    navController.navigate(afterLogin) {
                        popUpTo(NavRoutes.HOME) { inclusive = false }
                        launchSingleTop=true
                    }
                },
                onBack = { navController.popBackStack() },
                onClearState = { viewModel.clearState() }
            )
        }

        composable(NavRoutes.REGISTER) {
            val viewModel: AuthViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()
            RegisterScreen(
                uiState = uiState,
                onRegisterClick = { username, email, pass, displayName ->
                    viewModel.register(username, email, pass, displayName)
                },
                onNavigateToLogin = {
                    viewModel.clearState()
                    navController.navigate(NavRoutes.LOGIN) {
                        popUpTo(NavRoutes.REGISTER) { inclusive = true }
                    }
                },
                onRegisterSuccess = {
                    viewModel.clearState()
                    navController.navigate(afterLogin) {
                        popUpTo(NavRoutes.HOME) { inclusive = false }
                        launchSingleTop=true
                    }
                },
                onBack = { navController.popBackStack() },
                onClearState = { viewModel.clearState() }
            )
        }

        composable(NavRoutes.HOME) {
            HomeScreen(signedIn=signedIn,onPlans={ navController.navigate(NavRoutes.PLANS) { launchSingleTop=true } },membership=memberState,onOpenLink=::openLink)
        }

        composable(NavRoutes.VIDEOS) {
            if(signedIn && currentUser?.canUpload!=true) { CreatorLoginPrompt { navController.navigate(NavRoutes.PROFILE) }; return@composable }
            if(!signedIn) { CreatorLoginPrompt { logInFor(NavRoutes.VIDEOS) }; return@composable }
            val viewModel: VideoLibraryViewModel = hiltViewModel()
            androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) {
                viewModel.startObserving()
                onPauseOrDispose { viewModel.stopObserving() }
            }
            val foldersVm: FoldersViewModel = hiltViewModel()
            val folders by foldersVm.state.collectAsState()
            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if(!foldersVm.state.value.loading) foldersVm.refresh() }
            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
            val state by viewModel.state.collectAsState()
            LaunchedEffect(folders.items,folders.loading,state.folderFilter) {
                if(folders.loaded && !folders.loading && folders.error==null && state.folderFilter !in setOf("ALL","UNFILED") && folders.items.none { it.id==state.folderFilter }) viewModel.setFolder("ALL")
            }
            VideoLibraryScreen(state,{ foldersVm.refresh(); viewModel.refresh() },{ id -> navController.navigate("details/$id") },{ navController.popBackStack() },
                viewModel::setSearchText,viewModel::search,viewModel::loadMore,viewModel.thumbnails,
                viewModel::setSort,viewModel::setProcessingFilter,viewModel::setSharingFilter,viewModel::resetView,
                folders,viewModel::setFolder,{ navController.navigate(NavRoutes.FOLDERS) },viewModel::setVisibleVideos)
        }
        composable(NavRoutes.FOLDERS) {
            if(signedIn && currentUser?.canUpload!=true) { CreatorLoginPrompt { navController.navigate(NavRoutes.PROFILE) }; return@composable }
            if(!signedIn) { CreatorLoginPrompt { logInFor(NavRoutes.FOLDERS) }; return@composable }
            val viewModel: FoldersViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()
            FoldersScreen(state,viewModel::refresh,viewModel::save,viewModel::remove,{ navController.popBackStack() })
        }
        composable(NavRoutes.PLAYER) { entry ->
            val viewModel: PlayerViewModel = hiltViewModel()
            val id=entry.arguments?.getString("videoId") ?: return@composable
            val identity by viewModel.identity.collectAsState()
            // Signed-in sessions still undergo the fresh server ownership/capability check in WatchProgressSession.open before media loads.
            if(viewModel.shareAccess.token(id).isNullOrBlank() && !signedIn) {
                VideoUnavailableScreen({ navController.popBackStack() })
            } else if(identity==null) {
                Column(Modifier.padding(24.dp)) {
                    Text("Unable to identify this session. Return Home and sign in again if needed.")
                    IconButton(onClick={ navController.popBackStack() }) { com.example.vidoora.ui.theme.UiIcon(com.example.vidoora.R.drawable.ic_ui_back,"Back") }
                }
            } else key(identity) {
                VideoPlayerScreen(id,viewModel.client,viewModel.shareAccess.token(id),progressStore=viewModel.progress,
                    identity=identity!!,currentIdentity=viewModel::currentIdentity,viewerAds=ads,maxHeight=memberState.maxHeight,adFree=memberState.adFree,onPlans={ navController.navigate(NavRoutes.PLANS) { launchSingleTop=true } },onDetails=if(signedIn) ({ navController.navigate("details/$id?viewer=true") }) else null,
                    history=viewModel.history,analytics=viewModel.analytics) { navController.popBackStack() }
            }
        }

        composable(NavRoutes.BLOCKED) {
            if(!signedIn) { CreatorLoginPrompt { logInFor(NavRoutes.BLOCKED) }; return@composable }
            val viewModel: BlockedUploadersViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()
            BlockedUploadersScreen(state,viewModel::refresh,viewModel::more,viewModel::unblock,{ navController.popBackStack() })
        }
        composable(NavRoutes.OPEN_LINK) {
            OpenVideoLinkScreen({ link ->
                openLink(link)
            },{ navController.popBackStack() })
        }
        composable(NavRoutes.DETAILS,arguments=listOf(androidx.navigation.navArgument("viewer") { type=androidx.navigation.NavType.BoolType; defaultValue=false })) { entry ->
            val viewModel: VideoDetailsViewModel = hiltViewModel()
            androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) {
                viewModel.startObserving()
                onPauseOrDispose { viewModel.stopObserving() }
            }
            val state by viewModel.state.collectAsState()
            val id=entry.arguments?.getString("videoId") ?: return@composable
            val context=LocalContext.current
            LaunchedEffect(navigation.shareAccess.token(id)) { viewModel.ensureCurrentAccess() }
            LaunchedEffect(viewModel) {
                viewModel.shareLinks.collect { link ->
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type="text/plain"; putExtra(Intent.EXTRA_TEXT,link)
                    },"Share Vidoora video"))
                }
            }
            // Server-verified owners may Watch from My Videos; others retain the complete-link entry gate.
            val viewerEntry=state.video?.isOwner==true || (entry.arguments?.getBoolean("viewer")==true && !navigation.shareAccess.token(id).isNullOrBlank())
            ViewerAdsSession(ads,VideoViewingPolicy.canWatch(viewerEntry,state.video?.canPlay==true))
            val adState by ads.state.collectAsState()
            LaunchedEffect(entry, currentUser?.id, state.video?.canPlay) {
                if (viewerEntry && state.video?.canPlay == true) {
                    navigation.analyticsStorage.watchIdentity()?.let { identity ->
                        navigation.analytics.open(id, navigation.shareAccess.token(id), identity, newVisit = true)
                    }
                }
            }
            DisposableEffect(entry,currentUser?.id) { onDispose { ads.cancelWatch() } }
            fun watch() {
                if(adState.watchBusy || !VideoViewingPolicy.canWatch(viewerEntry,state.video?.canPlay==true)) return
                ads.deferConsentForms()
                val expectedIdentity=currentUser?.id
                fun valid()=navController.currentBackStackEntry==entry && currentUser?.id==expectedIdentity
                fun proceed() {
                    entry.lifecycleScope.launch {
                        entry.lifecycle.withResumed { if(valid()) navController.navigate("player/$id") { launchSingleTop=true } }
                    }
                }
                viewModel.watch { _ ->
                    if(valid()) {
                        scope.launch {
                            membership.refresh()
                            ads.membershipChanged()
                            if(valid()) {
                                val activity=context.adsActivity()
                                if(activity==null) proceed() else ads.watch(activity,::valid,::proceed)
                            }
                        }
                    }
                }
            }
            VideoDetailsScreen(state.copy(loading=state.loading || adState.watchBusy),viewModel::refresh,viewModel::enableLinkSharing,viewModel::unpublish,viewModel::share,
                { watch() },{ navController.popBackStack() },viewModel::blockUploader,viewModel::startEditing,viewModel::cancelEditing,viewModel::saveDetails,
                viewModel::setEditTitle,viewModel::setEditDescription,viewModel::deleteVideo,viewModel::startMoving,viewModel::selectFolder,viewModel::saveFolderMove,viewModel::cancelMoving,signedIn,viewModel.thumbnails,viewerEntry=viewerEntry,
                onChangeThumbnail={ uri -> viewModel.changeThumbnail(context,uri) },onDownload={
                    if(!signedIn) logInFor(NavRoutes.DOWNLOADS)
                    else if(memberState.planId !in listOf("WEEK","MONTHLY","ADMIN")) navController.navigate(NavRoutes.PLANS)
                    else offline.start(id,navigation.shareAccess.token(id)){navController.navigate(NavRoutes.DOWNLOADS){launchSingleTop=true}}
                },viewerAds=ads)
        }
        composable(NavRoutes.PLANS) {
            var refreshing by remember { mutableStateOf(false) }
            com.example.vidoora.feature.membership.CheckoutMembershipPlansScreen(memberState,signedIn,currentUser?.id,
                onLogin={logInFor(NavRoutes.PLANS)},onPayments={navController.navigate(NavRoutes.PAYMENTS){launchSingleTop=true}},
                onBack={ navController.popBackStack() },refreshing=refreshing,onRefresh={
                    if(!refreshing) {
                        refreshing=true
                        scope.launch { try { membership.refresh(force=true) } finally { refreshing=false } }
                    }
                })
        }
        composable(NavRoutes.HISTORY) {
            val player: com.example.vidoora.feature.playback.PlayerViewModel = hiltViewModel()
            val identity by player.identity.collectAsState()
            val revision by player.history.changes.collectAsState()
            val thumbnails = remember(identity, player.client) {
                com.example.vidoora.feature.playback.PrivateThumbnailLoader(player.client)
            }
            val historyScope = rememberCoroutineScope()
            var videos by remember(identity) { mutableStateOf(emptyList<com.example.vidoora.feature.playback.WatchedVideo>()) }
            var loading by remember(identity) { mutableStateOf(true) }
            var failed by remember(identity) { mutableStateOf(false) }
            LaunchedEffect(identity, revision) {
                val owner = identity
                try {
                    videos = owner?.let { player.history.load(it) }.orEmpty()
                    failed = false
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failed = true
                } finally {
                    loading = false
                }
            }
            com.example.vidoora.feature.profile.HistoryScreen(
                videos = if (identity == player.currentIdentity()) videos else emptyList(),
                thumbnailLoader = thumbnails,
                identityKey = identity.toString(),
                loading = loading,
                failed = failed,
                onOpen = { video ->
                    if (identity != null && identity == player.currentIdentity()) {
                        openLink(SharedVideoLink(video.id, video.capability))
                    }
                },
                onRemove = { id ->
                    identity?.let { owner ->
                        historyScope.launch {
                            try {
                                player.history.remove(owner, id)
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                failed = true
                            }
                        }
                    }
                }
            ) {
                navController.navigate(NavRoutes.HOME) { popUpTo(NavRoutes.HOME);launchSingleTop=true }
            }
        }
        composable(NavRoutes.PAYMENTS) { entry ->
            val payments: com.example.vidoora.feature.membership.PaymentsViewModel = hiltViewModel()
            val observedPayments by payments.state.collectAsState()
            val paymentState=payments.owned(observedPayments)
            LaunchedEffect(payments,entry,signedIn,currentUser?.id) {
                if(signedIn) entry.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                    while(true) { payments.refresh();kotlinx.coroutines.delay(10_000) }
                }
            }
            com.example.vidoora.feature.profile.PaymentHistoryScreen(signedIn,{navController.popBackStack()},
                {logInFor(NavRoutes.PAYMENTS)},{navController.navigate(NavRoutes.PLANS) {launchSingleTop=true}},
                {paymentReference="";navController.navigate(NavRoutes.SUPPORT) {launchSingleTop=true}},records={
                    com.example.vidoora.feature.membership.PaymentRecordsContent(paymentState,
                        {scope.launch{payments.refresh()}},{scope.launch{payments.refresh(more=true)}},payments::retry,
                        {reference->paymentReference=reference;navController.navigate(NavRoutes.SUPPORT) {launchSingleTop=true}})
                    currentUser?.id?.let {account ->com.example.vidoora.feature.membership.RestorePurchasesPanel(account,payments,payments.config)}
                })
        }
        composable(NavRoutes.DOWNLOADS) {
            val quota by offline.quota.collectAsState()
            val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(currentUser?.id,lifecycle) {
                offline.quota.value=null
                if(currentUser!=null) lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                    while(true) {offline.refreshQuota();kotlinx.coroutines.delay(5000)}
                }
            }
            val items by offline.items.collectAsState()
            val error by offline.error.collectAsState()
            com.example.vidoora.feature.downloads.PrivateDownloadsScreen(items,currentUser?.id,{navController.popBackStack()},
                {logInFor(NavRoutes.DOWNLOADS)},{navController.navigate(NavRoutes.PLANS){launchSingleTop=true}},
                {item->navController.navigate("offline/${item.id}")},offline::pause,offline::resume,offline::remove,error,quota)
        }
        composable("offline/{downloadId}") {entry->
            val items by offline.items.collectAsState()
            val id=entry.arguments?.getString("downloadId")
            val item=items.firstOrNull{it.id==id && it.account==currentUser?.id}
            if(item!=null) com.example.vidoora.feature.downloads.PrivateOfflinePlayer(item,offline.repository){navController.popBackStack()}
            else com.example.vidoora.feature.playback.VideoUnavailableScreen({navController.popBackStack()})
        }
        composable("analytics") {
            if (currentUser?.canUpload == true) {
                val model: com.example.vidoora.feature.analytics.CreatorAnalyticsViewModel = hiltViewModel()
                val admin = currentUser?.role == "ADMIN"
                key(currentUser?.id, admin) {
                    com.example.vidoora.feature.analytics.CreatorAnalyticsScreen(model, admin) { navController.popBackStack() }
                }
            } else LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(NavRoutes.SUPPORT) {
            val context=LocalContext.current
            val user = currentUser
            if (user == null) {
                androidx.compose.material3.Scaffold(topBar = {
                    com.example.vidoora.ui.theme.ReferenceHeader("Support centre", { navController.popBackStack() })
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Log in to contact Vidoora support")
                        com.example.vidoora.ui.theme.PrimaryAction("Log in", { logInFor(NavRoutes.SUPPORT) })
                    }
                }
            } else com.example.vidoora.feature.profile.SupportCentreScreen(user.id,{navController.popBackStack()},initialReference=paymentReference) { topic,reference,message ->
                val intent=com.example.vidoora.feature.profile.supportEmailIntent(user.id,topic,reference,message)
                try { context.startActivity(Intent.createChooser(intent,"Contact Vidoora support")) }
                catch (_: android.content.ActivityNotFoundException) { scope.launch { snackbar.showSnackbar("No email app found. Copy the support email address.") } }
            }
        }
        composable(NavRoutes.SETTINGS) {
            val isAdmin = currentUser?.role == "ADMIN" && currentUser?.isSuspended == false
            val manageUploaders: (() -> Unit)? = if (isAdmin) {
                { navController.navigate("manage_uploaders") { launchSingleTop = true } }
            } else null
            val uploadHealth: (() -> Unit)? = if (isAdmin) {
                { navController.navigate("upload_health") { launchSingleTop = true } }
            } else null
            val adminPayments: (() -> Unit)? = if (isAdmin) {
                { navController.navigate("admin_payments") { launchSingleTop = true } }
            } else null
            val adAnalytics: (() -> Unit)? = if(isAdmin) { {navController.navigate("ad_analytics") {launchSingleTop=true}} } else null
            val creatorCode: (() -> Unit)? = if(isAdmin) { {navController.navigate("creator_code") {launchSingleTop=true}} } else null
            com.example.vidoora.feature.profile.AccountSettingsScreen(memberState,ads,{navController.popBackStack()},
                {navController.navigate(NavRoutes.PLANS) {launchSingleTop=true}}, manageUploaders, uploadHealth, adminPayments, adAnalytics, creatorCode,
                if(isAdmin) ({navController.navigate("service_health") {launchSingleTop=true}}) else null)
        }
        composable("service_health") {
            if(currentUser?.role=="ADMIN" && currentUser?.isSuspended==false) {
                val model: com.example.vidoora.feature.analytics.CreatorAnalyticsViewModel = hiltViewModel()
                key(currentUser?.id) { com.example.vidoora.feature.analytics.ServiceHealthScreen(model) {navController.popBackStack()} }
            } else LaunchedEffect(Unit) {navController.popBackStack()}
        }
        composable("ad_analytics") {
            if(currentUser?.role=="ADMIN" && currentUser?.isSuspended==false) {
                val model: com.example.vidoora.feature.analytics.CreatorAnalyticsViewModel = hiltViewModel()
                key(currentUser?.id) {com.example.vidoora.feature.analytics.AdAnalyticsScreen(model) {navController.popBackStack()}}
            } else LaunchedEffect(Unit) {navController.popBackStack()}
        }
        composable("creator_code") {
            if(currentUser?.role=="ADMIN" && currentUser?.isSuspended==false) {
                val model: com.example.vidoora.feature.analytics.CreatorAnalyticsViewModel = hiltViewModel()
                key(currentUser?.id) {com.example.vidoora.feature.analytics.CreatorCodeScreen(model) {navController.popBackStack()}}
            } else LaunchedEffect(Unit) {navController.popBackStack()}
        }
        composable("admin_payments") {
            if (currentUser?.role == "ADMIN" && currentUser?.isSuspended == false) {
                val model: com.example.vidoora.feature.analytics.CreatorAnalyticsViewModel = hiltViewModel()
                key(currentUser?.id) {
                    com.example.vidoora.feature.analytics.AdminPaymentsScreen(model) { navController.popBackStack() }
                }
            } else LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable("upload_health") {
            if (currentUser?.role == "ADMIN" && currentUser?.isSuspended == false) {
                val model: com.example.vidoora.feature.analytics.CreatorAnalyticsViewModel = hiltViewModel()
                key(currentUser?.id) {
                    com.example.vidoora.feature.analytics.UploadHealthScreen(model) { navController.popBackStack() }
                }
            } else LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable("manage_uploaders") {
            if (currentUser?.role == "ADMIN" && currentUser?.isSuspended == false) {
                val model: com.example.vidoora.feature.analytics.CreatorAnalyticsViewModel = hiltViewModel()
                key(currentUser?.id) {
                    com.example.vidoora.feature.analytics.RemoveUploaderScreen(model) { navController.popBackStack() }
                }
            } else LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(NavRoutes.PROFILE) {
            val viewModel: ProfileViewModel = hiltViewModel()
            val currentUser by viewModel.userProfile.collectAsState()
            val loggingOut by viewModel.loggingOut.collectAsState()
            val logoutError by viewModel.logoutError.collectAsState()
            ProfileScreen(
                creatorInviteContent={com.example.vidoora.feature.profile.CreatorInvitePanel()},
                onBlockedClick={navController.navigate(NavRoutes.BLOCKED)},
                user = currentUser,
                onAnalytics={navController.navigate("analytics") { launchSingleTop=true }},
                onBackClick = { navController.popBackStack() },
                onLoginClick = { logInFor(NavRoutes.PROFILE) },
                onCreateAccount = { afterLogin=NavRoutes.PROFILE; navController.navigate(NavRoutes.REGISTER) { launchSingleTop=true } },
                onPayments={navController.navigate(NavRoutes.PAYMENTS) {launchSingleTop=true}},
                onDownloads={navController.navigate(NavRoutes.DOWNLOADS) {launchSingleTop=true}},
                onSupport={paymentReference="";navController.navigate(NavRoutes.SUPPORT) {launchSingleTop=true}},
                onSettings={navController.navigate(NavRoutes.SETTINGS) {launchSingleTop=true}},
                loggingOut=loggingOut,logoutError=logoutError,
                ads=ads,membership=memberState,onPlans={ navController.navigate(NavRoutes.PLANS) { launchSingleTop=true } },
                onLogoutClick = {
                    viewModel.logout { notice ->
                        if(notice!=null) scope.launch { snackbar.showSnackbar(notice) }
                        navController.navigate(NavRoutes.HOME) {
                            popUpTo(NavRoutes.HOME) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(NavRoutes.UPLOAD) {
            if(currentUser?.canUpload!=true) { CreatorLoginPrompt { logInFor(NavRoutes.PROFILE) }; return@composable }
            com.example.vidoora.feature.upload.CreatorHubScreen(signedIn,{logInFor(NavRoutes.UPLOAD)},
                {navController.navigate(NavRoutes.NEW_UPLOAD) {launchSingleTop=true}},
                {navController.navigate(NavRoutes.VIDEOS) {launchSingleTop=true}},
                {navController.navigate(NavRoutes.UPLOAD_QUEUE) {launchSingleTop=true}},
                {navController.navigate(NavRoutes.FOLDERS) {launchSingleTop=true}},
                {navController.navigate(NavRoutes.BLOCKED) {launchSingleTop=true}})
        }
        composable(NavRoutes.NEW_UPLOAD) {
            if(currentUser?.canUpload!=true) { CreatorLoginPrompt { logInFor(NavRoutes.PROFILE) }; return@composable }
            if(!signedIn) { CreatorLoginPrompt { logInFor(NavRoutes.NEW_UPLOAD) }; return@composable }
            val viewModel: UploadViewModel = hiltViewModel()
            val selectedVideo by viewModel.selectedVideo.collectAsState()
            val formState by viewModel.formState.collectAsState()

            UploadFormScreen(
                selectedVideo = selectedVideo,
                formState = formState,
                onVideoSelected = { uriStr, name, size, mime ->
                    viewModel.onVideoSelected(uriStr, name, size, mime)
                },
                onStartUploadClick = { title, desc, vis ->
                    viewModel.startUpload(title, desc, vis)
                },
                onViewQueueClick = {
                    navController.navigate(NavRoutes.UPLOAD_QUEUE)
                },
                onBackClick = { navController.popBackStack() },
                onClearState = { viewModel.clearFormState() }
            )
        }

        composable(NavRoutes.UPLOAD_QUEUE) {
            if(signedIn && currentUser?.canUpload!=true) { CreatorLoginPrompt { navController.navigate(NavRoutes.PROFILE) }; return@composable }
            if(!signedIn) { CreatorLoginPrompt { logInFor(NavRoutes.UPLOAD_QUEUE) }; return@composable }
            val viewModel: UploadViewModel = hiltViewModel()
            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { viewModel.refreshPreviews() }
            val tasks by viewModel.uploadTasks.collectAsState()
            val queueLifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(currentUser?.id,queueLifecycle) {
                viewModel.clearProcessing()
                queueLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                    while(true) {viewModel.refreshProcessing();kotlinx.coroutines.delay(5000)}
                }
            }
            val actionError by viewModel.actionError.collectAsState()

            UploadQueueScreen(
                tasks = tasks,
                processing = viewModel.processing,
                actionError = actionError,
                onDeleteClick = viewModel::deleteUpload,
                thumbnailLoader = viewModel.thumbnails,
                onOpenVideo = { id -> navController.navigate("details/$id") },
                onPauseClick = { taskId -> viewModel.pauseUpload(taskId) },
                onResumeClick = { taskId -> viewModel.resumeUpload(taskId) },
                onRetryClick = { taskId -> viewModel.retryUpload(taskId) },
                onCancelClick = { taskId, videoId, sessionId ->
                    viewModel.cancelUpload(taskId, videoId, sessionId)
                },
                onNewUploadClick = {
                    navController.navigate(NavRoutes.NEW_UPLOAD) {launchSingleTop=true}
                },
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
}
@Composable
private fun CreatorLoginPrompt(onLogin: ()->Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Upload your videos",style=MaterialTheme.typography.headlineSmall)
        Text("Log in or create an account to upload and manage your videos.")
        Button(shape=androidx.compose.material3.MaterialTheme.shapes.small,onClick=onLogin) { Text("Log in / Create account") }
    }
}


















