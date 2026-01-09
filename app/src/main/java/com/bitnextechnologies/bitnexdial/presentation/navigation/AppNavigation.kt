package com.bitnextechnologies.bitnexdial.presentation.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.bitnextechnologies.bitnexdial.presentation.screens.*
import kotlin.math.absoluteValue

/**
 * Navigation routes
 */
sealed class Screen(val route: String) {
    object Login : Screen("login")
    object ForgotPassword : Screen("forgot_password")
    object Dialer : Screen("dialer")
    object Recents : Screen("recents")
    object Contacts : Screen("contacts")
    object Messages : Screen("messages")
    object Voicemail : Screen("voicemail")
    object Settings : Screen("settings")

    // Detail screens
    object ContactDetail : Screen("contact/{contactId}") {
        fun createRoute(contactId: String) = "contact/$contactId"
    }
    object Conversation : Screen("conversation/{conversationId}?contactName={contactName}") {
        fun createRoute(conversationId: String, contactName: String? = null): String {
            val encodedName = contactName?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            return if (encodedName != null) {
                "conversation/$conversationId?contactName=$encodedName"
            } else {
                "conversation/$conversationId"
            }
        }
    }
    object CallHistory : Screen("call_history/{phoneNumber}") {
        fun createRoute(phoneNumber: String) = "call_history/$phoneNumber"
    }
    object NewMessage : Screen("new_message")
    object About : Screen("about")
    object SpeedDial : Screen("speed_dial")
    object CallAnalytics : Screen("call_analytics")
    object Recordings : Screen("recordings")

    // Add Contact with optional prefill phone
    object AddContact : Screen("add_contact?phone={phone}") {
        fun createRoute(phone: String = "") = if (phone.isNotEmpty()) {
            "add_contact?phone=$phone"
        } else {
            "add_contact"
        }
    }
}

/**
 * Bottom navigation items with short labels for compact display
 */
sealed class BottomNavItem(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Dialer : BottomNavItem(
        route = Screen.Dialer.route,
        title = "Keypad",
        selectedIcon = Icons.Filled.Dialpad,
        unselectedIcon = Icons.Outlined.Dialpad
    )
    object Recents : BottomNavItem(
        route = Screen.Recents.route,
        title = "Recents",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History
    )
    object Contacts : BottomNavItem(
        route = Screen.Contacts.route,
        title = "Contacts",
        selectedIcon = Icons.Filled.People,
        unselectedIcon = Icons.Outlined.People
    )
    object Messages : BottomNavItem(
        route = Screen.Messages.route,
        title = "Chats",  // Shortened from "Messages" for better fit
        selectedIcon = Icons.Filled.Message,
        unselectedIcon = Icons.Outlined.Message
    )
    object Voicemail : BottomNavItem(
        route = Screen.Voicemail.route,
        title = "VM",  // Shortened from "Voicemail" for better fit
        selectedIcon = Icons.Filled.Voicemail,
        unselectedIcon = Icons.Outlined.Voicemail
    )
}

val bottomNavItems = listOf(
    BottomNavItem.Dialer,
    BottomNavItem.Recents,
    BottomNavItem.Contacts,
    BottomNavItem.Messages,
    BottomNavItem.Voicemail
)

/**
 * Swipe threshold for tab navigation (in pixels)
 */
private const val SWIPE_THRESHOLD = 100f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    navController: NavHostController,
    isLoggedIn: Boolean,
    missedCallCount: Int = 0,
    unreadMessageCount: Int = 0,
    unreadVoicemailCount: Int = 0,
    onMakeCall: (String, String?) -> Unit,  // (phoneNumber, contactName)
    onLoginSuccess: () -> Unit = {}
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Determine if bottom nav should be shown - show immediately for main tabs
    // Use the start destination when currentRoute is null (initial load)
    val effectiveRoute = currentRoute ?: if (isLoggedIn) Screen.Dialer.route else null
    val showBottomNav = effectiveRoute in bottomNavItems.map { it.route }

    // Get current tab index for highlighting
    val currentTabIndex = bottomNavItems.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    // Swipe gesture state
    var swipeOffset by remember { mutableFloatStateOf(0f) }

    // Navigate to adjacent tab based on swipe direction
    fun navigateToTab(index: Int) {
        if (index in bottomNavItems.indices) {
            navController.navigate(bottomNavItems[index].route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (isLoggedIn && showBottomNav) {
                NavigationBar {
                    bottomNavItems.forEachIndexed { index, item ->
                        val selected = currentRoute == item.route

                        // Get badge count for this item (WhatsApp-style)
                        val badgeCount = when (item) {
                            BottomNavItem.Recents -> missedCallCount
                            BottomNavItem.Messages -> unreadMessageCount
                            BottomNavItem.Voicemail -> unreadVoicemailCount
                            else -> 0
                        }

                        NavigationBarItem(
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (badgeCount > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.error
                                            ) {
                                                Text(
                                                    text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.title
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = item.title,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (isLoggedIn) Screen.Dialer.route else Screen.Login.route,
            modifier = Modifier
                .padding(innerPadding)
                .then(
                    if (showBottomNav) {
                        Modifier.pointerInput(currentTabIndex) {
                            detectHorizontalDragGestures(
                                onDragStart = { swipeOffset = 0f },
                                onDragEnd = {
                                    // Navigate based on swipe direction
                                    when {
                                        swipeOffset > SWIPE_THRESHOLD -> {
                                            // Swiped right - go to previous tab
                                            navigateToTab(currentTabIndex - 1)
                                        }
                                        swipeOffset < -SWIPE_THRESHOLD -> {
                                            // Swiped left - go to next tab
                                            navigateToTab(currentTabIndex + 1)
                                        }
                                    }
                                    swipeOffset = 0f
                                },
                                onDragCancel = { swipeOffset = 0f },
                                onHorizontalDrag = { _, dragAmount ->
                                    swipeOffset += dragAmount
                                }
                            )
                        }
                    } else Modifier
                ),
            enterTransition = {
                fadeIn(animationSpec = tween(150))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(150))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(150))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(150))
            }
        ) {
            // Authentication
            composable(Screen.Login.route) {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(Screen.Dialer.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onForgotPassword = {
                        navController.navigate(Screen.ForgotPassword.route)
                    }
                )
            }

            composable(Screen.ForgotPassword.route) {
                ForgotPasswordScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            // Main screens with swipe support
            composable(Screen.Dialer.route) {
                DialerScreen(
                    onMakeCall = { phoneNumber -> onMakeCall(phoneNumber, null) },
                    onNavigateToContacts = {
                        navController.navigate(Screen.Contacts.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onAddContact = { phoneNumber ->
                        // Navigate to Add Contact screen with phone number pre-filled
                        navController.navigate(Screen.AddContact.createRoute(phoneNumber))
                    }
                )
            }

            composable(Screen.Recents.route) {
                RecentsScreen(
                    onCallClick = { phoneNumber, contactName ->
                        onMakeCall(phoneNumber, contactName)
                    },
                    onContactClick = { contactId ->
                        navController.navigate(Screen.ContactDetail.createRoute(contactId))
                    },
                    onNavigateToAnalytics = {
                        navController.navigate(Screen.CallAnalytics.route)
                    }
                )
            }

            composable(Screen.Contacts.route) {
                ContactsScreen(
                    onContactClick = { contactId ->
                        navController.navigate(Screen.ContactDetail.createRoute(contactId))
                    },
                    onCallClick = { phoneNumber ->
                        onMakeCall(phoneNumber, null)  // Contact name will be looked up
                    },
                    onMessageClick = { phoneNumber, contactName ->
                        navController.navigate(Screen.Conversation.createRoute(phoneNumber, contactName))
                    }
                )
            }

            composable(Screen.Messages.route) {
                MessagesScreen(
                    onConversationClick = { conversationId, contactName ->
                        navController.navigate(Screen.Conversation.createRoute(conversationId, contactName))
                    },
                    onNewMessage = {
                        navController.navigate(Screen.NewMessage.route)
                    }
                )
            }

            composable(Screen.Voicemail.route) {
                VoicemailScreen(
                    onCallBack = { phoneNumber ->
                        onMakeCall(phoneNumber, null)  // Contact name will be looked up
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onLogout = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToAbout = {
                        navController.navigate(Screen.About.route)
                    },
                    onNavigateToSpeedDial = {
                        navController.navigate(Screen.SpeedDial.route)
                    },
                    onNavigateToRecordings = {
                        navController.navigate(Screen.Recordings.route)
                    }
                )
            }

            // Speed Dial Screen
            composable(Screen.SpeedDial.route) {
                SpeedDialScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            // Call Analytics Screen
            composable(Screen.CallAnalytics.route) {
                CallAnalyticsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            // Recordings Screen
            composable(Screen.Recordings.route) {
                RecordingsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            // Detail screens
            composable(
                route = Screen.ContactDetail.route,
                arguments = listOf(navArgument("contactId") { type = NavType.StringType })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                ContactDetailScreen(
                    contactId = contactId,
                    onCallClick = { phoneNumber ->
                        onMakeCall(phoneNumber, null)  // Contact name will be looked up in InCallViewModel
                    },
                    onMessageClick = { phoneNumber, contactName ->
                        navController.navigate(Screen.Conversation.createRoute(phoneNumber, contactName))
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Screen.Conversation.route,
                arguments = listOf(
                    navArgument("conversationId") { type = NavType.StringType },
                    navArgument("contactName") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
                val contactName = backStackEntry.arguments?.getString("contactName")?.let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
                }
                ConversationScreen(
                    conversationId = conversationId,
                    contactName = contactName,
                    onCallClick = { phoneNumber ->
                        onMakeCall(phoneNumber, contactName)  // Pass contact name from conversation
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            // New Message Screen
            composable(Screen.NewMessage.route) {
                NewMessageScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onContactSelected = { phoneNumber ->
                        navController.navigate(Screen.Conversation.createRoute(phoneNumber)) {
                            popUpTo(Screen.NewMessage.route) { inclusive = true }
                        }
                    }
                )
            }

            // About Screen
            composable(Screen.About.route) {
                AboutScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            // Add Contact Screen (reuses ContactsScreen with prefill)
            composable(
                route = Screen.AddContact.route,
                arguments = listOf(
                    navArgument("phone") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { backStackEntry ->
                val prefillPhone = backStackEntry.arguments?.getString("phone") ?: ""
                ContactsScreen(
                    onContactClick = { contactId ->
                        navController.navigate(Screen.ContactDetail.createRoute(contactId))
                    },
                    onCallClick = { phoneNumber ->
                        onMakeCall(phoneNumber, null)  // Contact name will be looked up
                    },
                    onMessageClick = { phoneNumber, contactName ->
                        navController.navigate(Screen.Conversation.createRoute(phoneNumber, contactName))
                    },
                    prefillPhone = prefillPhone
                )
            }
        }
    }
}
