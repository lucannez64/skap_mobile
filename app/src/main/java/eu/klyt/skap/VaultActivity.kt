package eu.klyt.skap

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.Image
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.klyt.skap.lib.*
import eu.klyt.skap.ui.theme.SkapTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import dev.medzik.otp.OTPParameters
import dev.medzik.otp.TOTPGenerator
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.DpOffset
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import eu.klyt.skap.lib.PasswordAuditResult
import eu.klyt.skap.lib.PasswordSecurityAuditor
import eu.klyt.skap.lib.GlobalSecurityAuditResult
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.BugReport

class VaultActivity : ComponentActivity() {
    private var clientEx: ClientEx? = null
    private var token: String? = null

    fun getClientEx(): ClientEx? = clientEx
    fun getToken(): String? = token

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Récupérer le ClientEx et le token depuis l'intent
        val clientExBytes = intent.getByteArrayExtra("client_ex_bytes")
        token = intent.getStringExtra("token")

        if (clientExBytes == null || token == null) {
            Toast.makeText(this, "Erreur: données de connexion manquantes", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Décoder le ClientEx
        clientEx = Decoded.decodeClientEx(clientExBytes)

        if (clientEx == null) {
            Toast.makeText(this, "Erreur: impossible de décoder les données client", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            SkapTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1D1B21)
                ) {
                    VaultScreen(
                        clientEx = clientEx!!,
                        token = token!!,
                        onLogout = {
                            finish()
                        }
                    )
                }
            }
        }
    }
}


@Composable
fun VaultScreen(
    clientEx: ClientEx,
    token: String,
    onLogout: () -> Unit
) {
    // État pour la langue
    val context = LocalContext.current
    var language by remember { mutableStateOf(getLanguagePreference(context)) }
    val translations = getTranslations(language)
    
    // État pour le mode hors ligne
    val offlineStorageManager = remember { OfflineStorageManager.getInstance(context) }
    var isOfflineModeEnabled by remember { mutableStateOf(offlineStorageManager.isOfflineModeEnabled()) }
    var showOfflineConfirmDialog by remember { mutableStateOf(false) }

    // État pour les mots de passe
    var passwords by remember { mutableStateOf<List<Pair<Password, Uuid>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // État pour les partages
    var sharedPasswords by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var sharedPasswordsStatuses by remember { mutableStateOf<Map<String, List<ShareStatus>>>(emptyMap()) }
    
    // État pour les mots de passe partagés en attente (pending)
    var pendingSharedPasswords by remember { mutableStateOf<List<Quadruple<Password, Uuid, Uuid, ShareStatus>>>(emptyList()) }

    // État pour le partage actuel
    var currentSharingPassword by remember { mutableStateOf<Password?>(null) }
    var currentSharingUuid by remember { mutableStateOf<Uuid?>(null) }
    var showShareModal by remember { mutableStateOf(false) }
    var currentSharedEmails by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentSharedStatuses by remember { mutableStateOf<List<ShareStatus>>(emptyList()) }

    // État pour les mots de passe rejetés
    var showRejectedPasswordsModal by remember { mutableStateOf(false) }
    
    // État pour l'audit de sécurité global
    var showSecurityAuditModal by remember { mutableStateOf(false) }
    var isSecurityAuditLoading by remember { mutableStateOf(false) }
    var securityAuditResult by remember { mutableStateOf<GlobalSecurityAuditResult?>(null) }
    
    // État pour le debug hors ligne
    var showOfflineDebugDialog by remember { mutableStateOf(false) }

    // État pour la recherche
    var searchQuery by remember { mutableStateOf("") }
    
    // État pour l'ajout/édition
    var showAddForm by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var newRecord by remember { mutableStateOf(Password("", null, "", null, null, null)) }

    val coroutineScope = rememberCoroutineScope()

    // Charger les mots de passe
    LaunchedEffect(Unit) {
        loadPasswords(
            clientEx = clientEx,
            token = token,
            coroutineScope = coroutineScope,
            onError = { errorMsg ->
                error = errorMsg
                isLoading = false
            },
            onSuccess = { loadedPasswords ->
                passwords = loadedPasswords.first
                // Stocker tous les mots de passe partagés (tous statuts)
                pendingSharedPasswords = loadedPasswords.second
                passwords = passwords + pendingSharedPasswords.filter {
                    it ->
                    it.fourth == ShareStatus.Accepted
                }.map { it -> Pair(it.first, it.second) }
                isLoading = false

                // Charger les informations de partage
                loadSharedInfo(
                    clientEx = clientEx,
                    token = token,
                    coroutineScope = coroutineScope,
                    onSuccess = { sharedInfo, sharedStatuses ->
                        sharedPasswords = sharedInfo
                        sharedPasswordsStatuses = sharedStatuses
                    }
                )
            }
        )
    }

    // Filtrer les mots de passe selon la recherche
    val filteredPasswords = remember(passwords, searchQuery, pendingSharedPasswords) {
        // Exclure les mots de passe rejetés
        val passwordsWithoutRejected = passwords.filter { (_, passwordUuid) ->
            !pendingSharedPasswords.any { shared -> 
                shared.second == passwordUuid && shared.fourth == ShareStatus.Rejected 
            }
        }
        
        if (searchQuery.isEmpty()) {
            passwordsWithoutRejected.sortedBy { p -> p.first.url ?: p.first.app_id ?: p.first.username }
        } else {
            passwordsWithoutRejected.filter { (password, _) ->
                password.username.contains(searchQuery, ignoreCase = true) ||
                password.app_id?.contains(searchQuery, ignoreCase = true) == true ||
                password.url?.contains(searchQuery, ignoreCase = true) == true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Bouton de profil en haut à droite
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top=68.dp, end=16.dp   )
        ) {
            var showProfileMenu by remember { mutableStateOf(false) }

            IconButton(
                onClick = { showProfileMenu = true },
                modifier = Modifier
                    .size(48.dp)
                    .background(secondaryTextColor, RoundedCornerShape(50))
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = if (language == "fr") "Profil" else "Profile",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            DropdownMenu(
                expanded = showProfileMenu,
                onDismissRequest = { showProfileMenu = false },
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(10.dp))
                    .width(220.dp)
                    .padding(top=8.dp)
                    .border(1.dp, Color.White, RoundedCornerShape(4.dp))

            ) {
                // Option de langue
                Text(
                    text = if (language == "fr") "Langue" else "Language",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1D1B21))
                        .padding(16.dp)
                )

                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Français",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = if (language == "fr") FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        language = "fr"
                        saveLanguagePreference(context, language)
                        showProfileMenu = false
                    },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    if (language == "fr") accentColor2 else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (language == "fr") {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.Black
                                )
                            }
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = Color.White,
                        leadingIconColor = Color.White,
                        trailingIconColor = Color.White,
                        disabledTextColor = Color.Gray,
                        disabledLeadingIconColor = Color.Gray,
                        disabledTrailingIconColor = Color.Gray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )

                DropdownMenuItem(
                    text = {
                        Text(
                            text = "English",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = if (language == "en") FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        language = "en"
                        saveLanguagePreference(context, language)
                        showProfileMenu = false
                    },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    if (language == "en") accentColor2 else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (language == "en") {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.Black
                                )
                            }
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = Color.White,
                        leadingIconColor = Color.White,
                        trailingIconColor = Color.White,
                        disabledTextColor = Color.Gray,
                        disabledLeadingIconColor = Color.Gray,
                        disabledTrailingIconColor = Color.Gray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )

                Divider(
                    color = Color.White.copy(alpha = 0.2f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // Option pour l'audit de sécurité
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (language == "fr") "Audit de sécurité" else "Security audit",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    },
                    onClick = {
                        showSecurityAuditModal = true
                        showProfileMenu = false
                    },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    accentColor2.copy(alpha = 0.2f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = accentColor2
                            )
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = Color.White,
                        leadingIconColor = Color.White,
                        trailingIconColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )

                // Option pour afficher les mots de passe rejetés
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (language == "fr") "Mots de passe rejetés" else "Rejected passwords",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    },
                    onClick = {
                        showRejectedPasswordsModal = true
                        showProfileMenu = false
                    },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    accentColor1.copy(alpha = 0.2f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = accentColor1
                            )
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = Color.White,
                        leadingIconColor = Color.White,
                        trailingIconColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )

                Divider(
                    color = Color.White.copy(alpha = 0.2f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // Option pour le mode hors ligne
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (language == "fr") "Mode hors ligne" else "Offline Mode",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    },
                    onClick = {
                        if (isOfflineModeEnabled) {
                            // Désactiver le mode hors ligne
                            offlineStorageManager.setOfflineModeEnabled(false)
                            offlineStorageManager.clearOfflineData()
                            isOfflineModeEnabled = false
                            Toast.makeText(
                                context,
                                if (language == "fr") "Mode hors ligne désactivé" else "Offline mode disabled",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            // Activer le mode hors ligne avec confirmation
                            showOfflineConfirmDialog = true
                        }
                        showProfileMenu = false
                    },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    if (isOfflineModeEnabled) accentColor2.copy(alpha = 0.2f) else secondaryTextColor.copy(alpha = 0.2f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isOfflineModeEnabled) Icons.Default.CloudOff else Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = if (isOfflineModeEnabled) accentColor2 else secondaryTextColor
                            )
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = Color.White,
                        leadingIconColor = Color.White,
                        trailingIconColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )

                // Debug option for offline storage
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (language == "fr") "Debug hors ligne" else "Offline Debug",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    },
                    onClick = {
                        showOfflineDebugDialog = true
                        showProfileMenu = false
                    },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    Color.Yellow.copy(alpha = 0.2f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = Color.Yellow
                            )
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = Color.White,
                        leadingIconColor = Color.White,
                        trailingIconColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )

                Divider(
                    color = Color.White.copy(alpha = 0.2f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // Option de déconnexion
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (language == "fr") "Déconnexion" else "Logout",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    onClick = {
                        onLogout()
                    },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    errorColor.copy(alpha = 0.2f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = null,
                                tint = errorColor
                            )
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = Color.White,
                        leadingIconColor = Color.White,
                        trailingIconColor = Color.White,
                        disabledTextColor = Color.Gray,
                        disabledLeadingIconColor = Color.Gray,
                        disabledTrailingIconColor = Color.Gray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp, top=80.dp, end=16.dp)
        ) {
            Text(
                text = if (language == "fr") "Coffre-fort" else "Vault",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 16.dp)
            )

            // Barre de recherche
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(if (language == "fr") "Rechercher..." else "Search...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .height(56.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor2,
                    unfocusedBorderColor = secondaryTextColor,
                    focusedContainerColor = Color(0xFF2A2730),
                    unfocusedContainerColor = Color(0xFF2A2730),
                    cursorColor = accentColor2,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp)
            )
            
            // Section pour les mots de passe partagés en attente
            if (pendingSharedPasswords.filter { it.fourth == ShareStatus.Pending }.isNotEmpty()) {
                Text(
                    text = if (language == "fr") "Mots de passe partagés en attente" else "Pending Shared Passwords",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor1,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                )
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .padding(bottom = 16.dp)
                ) {
                    items(pendingSharedPasswords.filter { it.fourth == ShareStatus.Pending }) { (password, passUuid, ownerUuid, status) ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF2A2730)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = password.url ?: password.app_id ?: "",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                ) {

                                    Text(
                                        text = password.username,
                                        fontSize = 15.sp,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    val result = acceptSharedPass(
                                                        token = token,
                                                        recipientUuid = clientEx.id.id!!,
                                                        ownerUuid = ownerUuid,
                                                        passUuid = passUuid
                                                    )

                                                    if (result.isSuccess) {
                                                        Toast.makeText(
                                                            context,
                                                            if (language == "fr") "Mot de passe accepté" else "Password accepted",
                                                            Toast.LENGTH_SHORT
                                                        ).show()

                                                        // Changer le statut du mot de passe à Accepted
                                                        pendingSharedPasswords = pendingSharedPasswords.map { item ->
                                                            if (item.second == passUuid && item.third == ownerUuid) {
                                                                // Changer le statut à Accepted
                                                                Quadruple(item.first, item.second, item.third, ShareStatus.Accepted)
                                                            } else {
                                                                item
                                                            }
                                                        }
                                                        
                                                        // Vérifier si le mot de passe existe déjà dans la liste avant de l'ajouter
                                                        if (!passwords.any { it.second == passUuid }) {
                                                            // Ajouter le mot de passe à la liste seulement s'il n'existe pas déjà
                                                            passwords = passwords + Pair(password, passUuid)
                                                        } else {
                                                            passwords = passwords.filter { it.second != passUuid }
                                                            passwords = passwords + Pair(password, passUuid)
                                                        }

                                                    } else {
                                                        Toast.makeText(
                                                            context,
                                                            if (language == "fr")
                                                                "Erreur: ${result.exceptionOrNull()?.message}"
                                                            else
                                                                "Error: ${result.exceptionOrNull()?.message}",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = accentColor2
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .height(44.dp)
                                                .wrapContentWidth(Alignment.CenterHorizontally)
                                        ) {
                                            Text(
                                                text = if (language == "fr") "Accepter" else "Accept",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.Black,
                                                modifier = Modifier
                                                    .wrapContentWidth(Alignment.CenterHorizontally)
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    val result = rejectSharedPass(
                                                        token = token,
                                                        recipientUuid = clientEx.id.id!!,
                                                        ownerUuid = ownerUuid,
                                                        passUuid = passUuid
                                                    )

                                                    if (result.isSuccess) {
                                                        Toast.makeText(
                                                            context,
                                                            if (language == "fr") "Mot de passe refusé" else "Password rejected",
                                                            Toast.LENGTH_SHORT
                                                        ).show()

                                                        // Mettre à jour la liste des mots de passe partagés avec le statut Rejected
                                                        pendingSharedPasswords = pendingSharedPasswords.map { item ->
                                                            if (item.second == passUuid && item.third == ownerUuid) {
                                                                // Changer le statut à Rejected
                                                                Quadruple(item.first, item.second, item.third, ShareStatus.Rejected)
                                                            } else {
                                                                item
                                                            }
                                                        }
                                                    } else {
                                                        Toast.makeText(
                                                            context,
                                                            if (language == "fr")
                                                                "Erreur: ${result.exceptionOrNull()?.message}"
                                                            else
                                                                "Error: ${result.exceptionOrNull()?.message}",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = errorColor
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .height(44.dp)
                                                .wrapContentWidth(Alignment.CenterHorizontally)
                                        ) {
                                            Text(
                                                text = if (language == "fr") "Refuser" else "Reject",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White,
                                                modifier = Modifier
                                                    .wrapContentWidth(Alignment.CenterHorizontally)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bouton d'ajout
            Button(
                onClick = { showAddForm = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor2
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = if (language == "fr") "Ajouter un mot de passe" else "Add Password",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1D1B21),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // Affichage du chargement
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = accentColor1,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            // Affichage des erreurs
            error?.let {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .background(errorColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = it,
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Liste des mots de passe
            if (!isLoading && error == null) {
                if (filteredPasswords.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (language == "fr") "Aucun mot de passe trouvé" else "No passwords found",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 16.sp
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = filteredPasswords,
                            key = { (_, uuid) -> uuid.toString() }
                        ) { (password, uuid) ->
                            val uuidStr = uuid.toString()
                            val isShared = sharedPasswords.containsKey(uuidStr) && sharedPasswords[uuidStr]?.isNotEmpty() == true
                            Log.i(null, "password: $password")
                            PasswordItem(
                                password = password,
                                uuid = uuid,
                                language = language,
                                clientEx = clientEx,
                                token = token,
                                isShared = isShared,
                                pendingSharedPasswords = pendingSharedPasswords,
                                coroutineScope = coroutineScope,
                                onEdit = { updatedPassword ->
                                    // Mettre à jour la liste des mots de passe avec le mot de passe modifié
                                    passwords = passwords.map { (p, u) ->
                                        if (u == uuid) updatedPassword to u else p to u
                                    }
                                    // TODO: Rerender this specific item

                                },
                                onDelete = {
                                    // Afficher une boîte de dialogue de confirmation avant la suppression
                                    val dialogTitle = if (language == "fr") "Confirmer la suppression" else "Confirm deletion"
                                    val dialogMessage = if (language == "fr")
                                        "Êtes-vous sûr de vouloir supprimer ce mot de passe ?"
                                    else
                                        "Are you sure you want to delete this password?"
                                    val confirmText = if (language == "fr") "Supprimer" else "Delete"
                                    val cancelText = if (language == "fr") "Annuler" else "Cancel"

                                    val builder = android.app.AlertDialog.Builder(context)
                                    builder.setTitle(dialogTitle)
                                        .setMessage(dialogMessage)
                                        .setPositiveButton(confirmText) { dialog, _ ->
                                            dialog.dismiss()
                                            // Procéder à la suppression
                                            coroutineScope.launch {
                                                deletePassword(
                                                    clientEx = clientEx,
                                                    token = token,
                                                    uuid = uuid,
                                                    onSuccess = {
                                                        // Mettre à jour la liste des mots de passe après suppression
                                                        passwords = passwords.filter { it.second != uuid }
                                                        Toast.makeText(
                                                            context,
                                                            if (language == "fr") "Mot de passe supprimé" else "Password deleted",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    },
                                                    onError = { errorMsg ->
                                                        Toast.makeText(
                                                            context,
                                                            if (language == "fr") "Erreur: $errorMsg" else "Error: $errorMsg",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                )
                                            }
                                        }
                                        .setNegativeButton(cancelText) { dialog, _ ->
                                            dialog.dismiss()
                                        }
                                        .show()
                                },
                                onShare = {
                                    // Afficher le modal de partage avec les informations de partage existantes
                                    currentSharingPassword = password
                                    currentSharingUuid = uuid
                                    currentSharedEmails = sharedPasswords[uuidStr] ?: emptyList()
                                    currentSharedStatuses = sharedPasswordsStatuses[uuidStr] ?: emptyList()
                                    showShareModal = true
                                },
                                onUpdateSharedPasswords = { updatedSharedPasswords ->
                                    pendingSharedPasswords = updatedSharedPasswords
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialogue de partage
    if (showShareModal && currentSharingPassword != null && currentSharingUuid != null) {
        SharePasswordDialog(
            password = currentSharingPassword!!,
            uuid = currentSharingUuid!!,
            sharedEmails = currentSharedEmails,
            sharedStatuses = currentSharedStatuses,
            language = language,
            clientEx = clientEx,
            token = token,
            onDismiss = { showShareModal = false },
            onShareSuccess = { email: String ->
                // Mettre à jour la liste des emails partagés
                val uuidStr = currentSharingUuid?.toString()
                val updatedEmails = (sharedPasswords[uuidStr] ?: emptyList()) + email
                val updatedStatuses = (sharedPasswordsStatuses[uuidStr] ?: emptyList()) + ShareStatus.Pending

                sharedPasswords = sharedPasswords.toMutableMap().apply {
                    put(uuidStr!!, updatedEmails)
                }
                sharedPasswordsStatuses = sharedPasswordsStatuses.toMutableMap().apply {
                    put(uuidStr!!, updatedStatuses)
                }

                // Mettre à jour les emails actuellement affichés
                currentSharedEmails = updatedEmails
                currentSharedStatuses = updatedStatuses
            },
            onUnshareSuccess = { email:String ->
                // Mettre à jour la liste des emails partagés
                val uuidStr = currentSharingUuid!!.toString()
                 val emailIndex = currentSharedEmails.indexOf(email)

                if (emailIndex >= 0) {
                    val updatedEmails = currentSharedEmails.filter { it != email }
                    val updatedStatuses = currentSharedStatuses.filterIndexed { index, _ -> index != emailIndex }

                    sharedPasswords = sharedPasswords.toMutableMap().apply {
                        put(uuidStr, updatedEmails)
                    }
                    sharedPasswordsStatuses = sharedPasswordsStatuses.toMutableMap().apply {
                        put(uuidStr, updatedStatuses)
                    }

                    // Mettre à jour les emails actuellement affichés
                    currentSharedEmails = updatedEmails
                    currentSharedStatuses = updatedStatuses
                }
            }
        )
    }

    // Ajouter le dialogue de création
    if (showAddForm) {
        AddPasswordDialog(
            language = language,
            clientEx = clientEx!!,
            token = token!!,
            onDismiss = { showAddForm = false },
            onAddSuccess = { newPassword, newUuid ->
                // Ajouter le nouveau mot de passe à la liste
                passwords = passwords + (newPassword to newUuid)
                showAddForm = false
            }
        )
    }

    // Fenêtre modale pour les mots de passe rejetés
    if (showRejectedPasswordsModal) {
        RejectedPasswordsDialog(
            language = language,
            clientEx = clientEx,
            token = token,
            pendingSharedPasswords = pendingSharedPasswords.filter { it.fourth == ShareStatus.Rejected },
            onDismiss = { showRejectedPasswordsModal = false },
            onAcceptAgain = { passUuid, ownerUuid, password ->
                // Lorsqu'un mot de passe est accepté à nouveau
                coroutineScope.launch {
                    val result = acceptSharedPass(
                        token = token,
                        recipientUuid = clientEx.id.id!!,
                        ownerUuid = ownerUuid,
                        passUuid = passUuid
                    )

                    if (result.isSuccess) {
                        Toast.makeText(
                            context,
                            if (language == "fr") "Mot de passe accepté" else "Password accepted",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Changer le statut du mot de passe à Accepted
                        pendingSharedPasswords = pendingSharedPasswords.map { item ->
                            if (item.second == passUuid && item.third == ownerUuid) {
                                // Changer le statut à Accepted
                                Quadruple(item.first, item.second, item.third, ShareStatus.Accepted)
                            } else {
                                item
                            }
                        }
                        
                        // Vérifier si le mot de passe existe déjà dans la liste avant de l'ajouter
                        if (!passwords.any { it.second == passUuid }) {
                            // Ajouter le mot de passe à la liste seulement s'il n'existe pas déjà
                            passwords = passwords + Pair(password, passUuid)
                        }
                    } else {
                        Toast.makeText(
                            context,
                            if (language == "fr")
                                "Erreur: ${result.exceptionOrNull()?.message}"
                            else
                                "Error: ${result.exceptionOrNull()?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }
    
    // Modale d'audit de sécurité
    if (showSecurityAuditModal) {
        SecurityAuditDialog(
            language = language,
            passwords = passwords.map { (password, uuid) -> uuid.toString() to password.password }.toMap(),
            onDismiss = { showSecurityAuditModal = false }
        )
    }
    
    // Dialogue de confirmation pour le mode hors ligne
    if (showOfflineConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showOfflineConfirmDialog = false },
            title = {
                Text(
                    text = if (language == "fr") "Activer le mode hors ligne" else "Enable Offline Mode",
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = if (language == "fr") 
                        "Cela sauvegardera tous vos mots de passe de manière sécurisée sur cet appareil. Vous pourrez y accéder même sans connexion Internet. Continuer ?"
                    else
                        "This will securely save all your passwords on this device. You'll be able to access them even without an internet connection. Continue?",
                    color = Color.White
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                             try {
                                 val userEmail = clientEx.id.email
                                 val clientId = clientEx.id.id?.toString() ?: ""
                                 
                                 if (passwords.isEmpty() && pendingSharedPasswords.filter { it.fourth == ShareStatus.Accepted }.isEmpty()) {
                                     Toast.makeText(
                                         context,
                                         if (language == "fr") "Aucune donnée à sauvegarder" else "No data to save",
                                         Toast.LENGTH_SHORT
                                     ).show()
                                     return@launch
                                 }
                                 
                                 val result = offlineStorageManager.saveOfflineData(
                                     passwords = passwords,
                                     sharedPasswords = pendingSharedPasswords.filter { it.fourth == ShareStatus.Accepted },
                                     userEmail = userEmail,
                                     clientId = clientId,
                                     clientEx = clientEx
                                 )
                                 
                                 if (result.isSuccess) {
                                     offlineStorageManager.setOfflineModeEnabled(true)
                                     isOfflineModeEnabled = true
                                     Toast.makeText(
                                         context,
                                         if (language == "fr") "Mode hors ligne activé - ${passwords.size} mots de passe sauvegardés" else "Offline mode enabled - ${passwords.size} passwords saved",
                                         Toast.LENGTH_SHORT
                                     ).show()
                                 } else {
                                     val errorMessage = result.exceptionOrNull()?.message ?: "Unknown error"
                                     Toast.makeText(
                                         context,
                                         if (language == "fr") "Erreur lors de l'activation: $errorMessage" else "Error enabling offline mode: $errorMessage",
                                         Toast.LENGTH_LONG
                                     ).show()
                                     Log.e("OfflineMode", "Failed to save offline data", result.exceptionOrNull())
                                 }
                             } catch (e: Exception) {
                                 Toast.makeText(
                                     context,
                                     if (language == "fr") "Erreur inattendue: ${e.message}" else "Unexpected error: ${e.message}",
                                     Toast.LENGTH_LONG
                                 ).show()
                                 Log.e("OfflineMode", "Unexpected error during offline mode activation", e)
                             }
                         }
                        showOfflineConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor2
                    )
                ) {
                    Text(
                        text = if (language == "fr") "Activer" else "Enable",
                        color = Color.Black
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = { showOfflineConfirmDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = secondaryTextColor
                    )
                ) {
                    Text(
                        text = if (language == "fr") "Annuler" else "Cancel",
                        color = Color.White
                    )
                }
            },
            containerColor = backgroundColor,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }
    
    // Dialogue de debug pour le mode hors ligne
    if (showOfflineDebugDialog) {
        var debugInfo by remember { mutableStateOf("Loading debug info...") }
        
        // Capture current values
        val currentPasswordsSize = passwords.size
        val currentSharedPasswordsSize = pendingSharedPasswords.filter { it.fourth == ShareStatus.Accepted }.size
        
        LaunchedEffect(Unit) {
            try {
                val hasOfflineData = offlineStorageManager.hasOfflineData()
                val isEnabled = offlineStorageManager.isOfflineModeEnabled()
                val userEmail = offlineStorageManager.getUserEmail()
                val clientId = offlineStorageManager.getClientId()
                
                // Try to get stored data info
                val kyQHash = try {
                    val hash = clientEx.c.kyQ.contentHashCode()
                    "Hash: $hash"
                } catch (e: Exception) {
                    "Error getting kyQ hash: ${e.message}"
                }
                
                val loadResult = try {
                    val result = offlineStorageManager.loadOfflineData()
                    if (result.isSuccess) {
                        val data = result.getOrNull()
                        "Load Success: ${data?.first?.size ?: 0} passwords, ${data?.second?.size ?: 0} shared"
                    } else {
                        "Load Failed: ${result.exceptionOrNull()?.message}"
                    }
                } catch (e: Exception) {
                    "Load Exception: ${e.message}"
                }
                
                debugInfo = buildString {
                    appendLine("=== OFFLINE DEBUG INFO ===")
                    appendLine("Offline Mode Enabled: $isEnabled")
                    appendLine("Has Offline Data: $hasOfflineData")
                    appendLine("User Email: $userEmail")
                    appendLine("Client ID: $clientId")
                    appendLine("kyQ Key $kyQHash")
                    appendLine("")
                    appendLine("=== LOAD TEST ===")
                    appendLine(loadResult)
                    appendLine("")
                    appendLine("=== CURRENT SESSION ===")
                    appendLine("Passwords in memory: $currentPasswordsSize")
                    appendLine("Shared passwords: $currentSharedPasswordsSize")
                }
            } catch (e: Exception) {
                debugInfo = "Error getting debug info: ${e.message}"
            }
        }
        
        AlertDialog(
            onDismissRequest = { showOfflineDebugDialog = false },
            title = {
                Text(
                    text = if (language == "fr") "Debug Mode Hors Ligne" else "Offline Mode Debug",
                    color = Color.White
                )
            },
            text = {
                LazyColumn {
                    item {
                        Text(
                            text = debugInfo,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showOfflineDebugDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor2
                    )
                ) {
                    Text(
                        text = if (language == "fr") "Fermer" else "Close",
                        color = Color.Black
                    )
                }
            },
            containerColor = backgroundColor,
            titleContentColor = Color.White,
            textContentColor = Color.White,
            modifier = Modifier.fillMaxWidth(0.9f)
        )
    }
}

@SuppressLint("SuspiciousIndentation")
@Composable
fun PasswordItem(
    password: Password,
    uuid: Uuid,
    language: String,
    clientEx: ClientEx,
    token: String,
    isShared: Boolean = false,
    pendingSharedPasswords: List<Quadruple<Password, Uuid, Uuid, ShareStatus>>,
    coroutineScope: CoroutineScope,
    onEdit: (Password) -> Unit,
    onDelete: () -> Unit,
    onShare: (Password) -> Unit,
    onUpdateSharedPasswords: (List<Quadruple<Password, Uuid, Uuid, ShareStatus>>) -> Unit
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRejectDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // États pour l'OTP
    var otpCode by remember { mutableStateOf<String?>(null) }
    var remainingSeconds by remember { mutableStateOf(0) }
    var periodSeconds by remember { mutableStateOf(30) }

    // Déterminer si ce mot de passe est un mot de passe partagé que j'ai accepté
    val uuidStr = uuid.toString()
    val sharedToMe = remember(pendingSharedPasswords) {
        pendingSharedPasswords.any { 
            it.second == uuid && it.fourth == ShareStatus.Accepted 
        }
    }
    
    // Gestion du cycle de vie pour l'OTP
    val lifecycleOwner = LocalLifecycleOwner.current
    var otpUpdateJob by remember { mutableStateOf<Job?>(null) }

    // Analyser et générer l'OTP si disponible
    DisposableEffect(password.otp, lifecycleOwner) {
        var isActive = true

        if (password.otp != null) {
            try {
                val tt = OtpUtils.fromUri(password.otp)
                if (tt == null) {
                    throw Exception("Couldn't decode the otp uri")
                }

                periodSeconds = tt.period
                val initialTimeMillis = System.currentTimeMillis()
                val initialElapsedSeconds = (initialTimeMillis / 1000) % periodSeconds
                remainingSeconds = periodSeconds - initialElapsedSeconds.toInt()
                otpCode = OtpUtils.generate(tt)

                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            isActive = true
                            if (otpUpdateJob == null || otpUpdateJob?.isActive == false) {
                                otpUpdateJob = CoroutineScope(Dispatchers.Main).launch {
                                        updateOtpCode(tt, periodSeconds) { newCode, newRemaining ->
                                            if (isActive) {
                                                otpCode = newCode
                                                remainingSeconds = newRemaining
                                        }
                                    }
                                }
                            }
                        }
                        Lifecycle.Event.ON_PAUSE -> {
                            isActive = false
                            otpUpdateJob?.cancel()
                            otpUpdateJob = null
                        }
                        else -> {}
                    }
                }

                lifecycleOwner.lifecycle.addObserver(observer)

                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    otpUpdateJob?.cancel()
                    otpUpdateJob = null
                }
            } catch (e: Exception) {
                Log.e("OTP", "Erreur lors de l'analyse de l'URI OTP: ${e.message}")
                otpCode = null
                onDispose { }
            }
        } else {
            onDispose { }
        }
    }

    val domain = remember(password.url) {
        if (password.url != null) {
            try {
                Uri.parse(password.url).host ?: password.url
            } catch (e: Exception) {
                password.url
            }
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBackgroundColor, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        pressOffset = DpOffset(offset.x.toDp(), 0.dp)
                        showContextMenu = true
                    }
                )
            }
            .padding(12.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icône pour URL ou App
                if (password.url != null) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data("https://www.google.com/s2/favicons?domain=$domain&sz=64")
                                .crossfade(true)
                                .memoryCachePolicy(coil3.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil3.request.CachePolicy.ENABLED)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            placeholder = androidx.compose.ui.graphics.vector.rememberVectorPainter(image = Icons.Default.Public),
                            error = androidx.compose.ui.graphics.vector.rememberVectorPainter(image = Icons.Default.Public),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = accentColor1,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = password.url ?: password.app_id ?: (if (language == "fr") "Service non spécifié" else "Unspecified service"),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        if (isShared) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = accentColor1.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(start = 8.dp, end = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = if (language == "fr") "Partagé" else "Shared",
                                        tint = backgroundColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (language == "fr") "Partagé" else "Shared",
                                        fontSize = 12.sp,
                                        color = backgroundColor,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        
                        // Badge pour les mots de passe partagés avec moi et acceptés
                        if (sharedToMe) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = accentColor2.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(start = 8.dp, end = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.People,
                                        contentDescription = if (language == "fr") "Partagé avec moi" else "Shared with me",
                                        tint = backgroundColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (language == "fr") "Partagé avec moi" else "Shared with me",
                                        fontSize = 12.sp,
                                        color = backgroundColor,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = password.username,
                        fontSize = 14.sp,
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // OTP section si disponible
            if (password.otp != null && otpCode != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF232027), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = otpCode!!,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor2
                        )

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("otp", otpCode)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(
                                    context,
                                    if (language == "fr") "Code 2FA copié" else "2FA code copied",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = if (language == "fr") "Copier" else "Copy",
                                tint = accentColor1,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(32.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = remainingSeconds.toFloat() / periodSeconds.toFloat(),
                            modifier = Modifier.fillMaxSize(),
                            color = when {
                                remainingSeconds <= 5 -> errorColor
                                remainingSeconds <= 10 -> Color(0xFFFFA500)
                                else -> accentColor2
                            },
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = remainingSeconds.toString(),
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = pressOffset,
            modifier = Modifier
                .background(backgroundColor)
                .width(200.dp)
        ) {
            // Copier le nom d'utilisateur
            DropdownMenuItem(
                text = { Text(if (language == "fr") "Copier le nom d'utilisateur" else "Copy username") },
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("username", password.username)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        context,
                        if (language == "fr") "Nom d'utilisateur copié" else "Username copied",
                        Toast.LENGTH_SHORT
                    ).show()
                    showContextMenu = false
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
            )

            // Copier le mot de passe
            DropdownMenuItem(
                text = { Text(if (language == "fr") "Copier le mot de passe" else "Copy password") },
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("password", password.password)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        context,
                        if (language == "fr") "Mot de passe copié" else "Password copied",
                        Toast.LENGTH_SHORT
                    ).show()
                    showContextMenu = false
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
            )

            Divider()

            // Actions différentes selon que le mot de passe est partagé avec moi ou non
            if (sharedToMe) {
                // Option pour rejeter le mot de passe partagé
                DropdownMenuItem(
                    text = { Text(if (language == "fr") "Rejeter ce partage" else "Reject sharing") },
                    onClick = {
                        showRejectDialog = true
                        showContextMenu = false
                    },
                    leadingIcon = { 
                        Icon(
                            Icons.Default.Delete, 
                            null,
                            tint = errorColor
                        ) 
                    }
                )
            } else {
                // Options standard pour les mots de passe m'appartenant
                DropdownMenuItem(
                    text = { Text(if (language == "fr") "Modifier" else "Edit") },
                    onClick = {
                        showEditDialog = true
                        showContextMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                )

                DropdownMenuItem(
                    text = { Text(if (language == "fr") "Partager" else "Share") },
                    onClick = {
                        onShare(password)
                        showContextMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.Share, null) }
                )

                DropdownMenuItem(
                    text = { Text(if (language == "fr") "Supprimer" else "Delete") },
                    onClick = {
                        showDeleteDialog = true
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            null,
                            tint = errorColor
                        )
                    }
                )
            }
        }
    }

    // N'afficher les dialogues que si c'est approprié selon le type de mot de passe
    if (!sharedToMe) {
        // Dialogue d'édition (seulement pour les mots de passe m'appartenant)
        if (showEditDialog) {
            EditPasswordDialog(
                password = password,
                uuid = uuid,
                language = language,
                clientEx = clientEx,
                token = token,
                onDismiss = { showEditDialog = false },
                onEditSuccess = { updatedPassword ->
                    onEdit(updatedPassword)
                    showEditDialog = false
                }
            )
        }

        // Dialogue de suppression (seulement pour les mots de passe m'appartenant)
        if (showDeleteDialog) {
            DeletePasswordDialog(
                password = password,
                language = language,
                clientEx = clientEx,
                token = token,
                uuid = uuid,
                onDismiss = { showDeleteDialog = false },
                onDeleteSuccess = {
                    onDelete()
                    showDeleteDialog = false
                }
            )
        }
    } else {
        // Dialogue pour rejeter un mot de passe partagé avec moi
        if (showRejectDialog) {
            AlertDialog(
                onDismissRequest = { showRejectDialog = false },
                title = {
                    Text(
                        text = if (language == "fr") "Rejeter ce partage" else "Reject sharing",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                text = {
                    Text(
                        text = if (language == "fr") 
                            "Êtes-vous sûr de vouloir rejeter ce mot de passe partagé ? Vous n'y aurez plus accès."
                        else
                            "Are you sure you want to reject this shared password? You will no longer have access to it.",
                        fontSize = 16.sp,
                        color = Color.White
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            // Trouve l'entrée correspondante dans les mots de passe partagés
                            val shared = pendingSharedPasswords.find { 
                                it.second == uuid && it.fourth == ShareStatus.Accepted 
                            }
                            
                            if (shared != null) {
                                val (_, passUuid, ownerUuid, _) = shared
                                
                                // Lancer le processus de rejet
                                coroutineScope.launch {
                                    val result = rejectSharedPass(
                                        token = token,
                                        recipientUuid = clientEx.id.id!!,
                                        ownerUuid = ownerUuid,
                                        passUuid = passUuid
                                    )

                                    if (result.isSuccess) {
                                        Toast.makeText(
                                            context,
                                            if (language == "fr") "Partage rejeté" else "Sharing rejected",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        
                                        // Changer l'element de la liste correspondant pour avoir le statut Rejected
                                        val updatedList = pendingSharedPasswords.map { item ->
                                            if (item.second == passUuid && item.third == ownerUuid && item.fourth == ShareStatus.Accepted) {
                                                // Changer le statut à Rejected
                                                Quadruple(item.first, item.second, item.third, ShareStatus.Rejected)
                                            } else {
                                                item
                                            }
                                        }
                                        onUpdateSharedPasswords(updatedList)
                                        
                                        // Fermer le dialogue immédiatement
                                        showRejectDialog = false
                                    } else {
                                        Toast.makeText(
                                            context,
                                            if (language == "fr")
                                                "Erreur: ${result.exceptionOrNull()?.message}"
                                            else
                                                "Error: ${result.exceptionOrNull()?.message}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                            
                            showRejectDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = errorColor,
                            contentColor = Color.White
                        )
                    ) {
                        Text(if (language == "fr") "Rejeter" else "Reject")
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showRejectDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3A3740),
                            contentColor = Color.White
                        )
                    ) {
                        Text(if (language == "fr") "Annuler" else "Cancel")
                    }
                },
                containerColor = backgroundColor,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
fun EditPasswordDialog(
    password: Password,
    uuid: Uuid,
    language: String,
    clientEx: ClientEx,
    token: String,
    onDismiss: () -> Unit,
    onEditSuccess: (Password) -> Unit
) {
    var editedPassword by remember { mutableStateOf(password) }
    var isEditing by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    var showPasswordGenerator by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (language == "fr") "Modifier le mot de passe" else "Edit password",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                if (editError != null) {
                    Text(
                        text = editError!!,
                        color = errorColor,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .background(errorColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .fillMaxWidth()
                    )
                }

                // URL ou App ID
                OutlinedTextField(
                    value = editedPassword.url ?: editedPassword.app_id ?: "",
                    onValueChange = {
                        if (editedPassword.url != null) {
                            editedPassword = editedPassword.copy(url = it)
                        } else {
                            editedPassword = editedPassword.copy(app_id = it)
                        }
                    },
                    label = {
                        Text(
                            text = if (language == "fr" && editedPassword.url != null) "URL" else if (language == "fr" && editedPassword.app_id != null) "App ID" else if (language == "en" && editedPassword.url != null) "URL" else "App ID",
                            fontSize = 16.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .padding(bottom = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor2,
                        unfocusedBorderColor = secondaryTextColor,
                        focusedContainerColor = Color(0xFF2A2730),
                        unfocusedContainerColor = Color(0xFF2A2730),
                        cursorColor = accentColor2,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp)
                )

                // Username
                OutlinedTextField(
                    value = editedPassword.username,
                    onValueChange = { editedPassword = editedPassword.copy(username = it) },
                    label = {
                        Text(
                            text = if (language == "fr") "Nom d'utilisateur" else "Username",
                            fontSize = 16.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .padding(bottom = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor2,
                        unfocusedBorderColor = secondaryTextColor,
                        focusedContainerColor = Color(0xFF2A2730),
                        unfocusedContainerColor = Color(0xFF2A2730),
                        cursorColor = accentColor2,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp)
                )

                // Password
                OutlinedTextField(
                    value = editedPassword.password,
                    onValueChange = { editedPassword = editedPassword.copy(password = it) },
                    label = {
                        Text(
                            text = if (language == "fr") "Mot de passe" else "Password",
                            fontSize = 16.sp
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPasswordGenerator = true }) {
                            Icon(
                                imageVector = Icons.Default.Casino,
                                contentDescription = if (language == "fr") "Générer" else "Generate",
                                tint = accentColor2
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .padding(bottom = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor2,
                        unfocusedBorderColor = secondaryTextColor,
                        focusedContainerColor = Color(0xFF2A2730),
                        unfocusedContainerColor = Color(0xFF2A2730),
                        cursorColor = accentColor2,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp)
                )

                // OTP (optionnel)
                OutlinedTextField(
                    value = editedPassword.otp ?: "",
                    onValueChange = { editedPassword = editedPassword.copy(otp = it) },
                    label = {
                        Text(
                            text = if (language == "fr") "2FA (optionnel)" else "2FA (optional)",
                            fontSize = 16.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .padding(bottom = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor2,
                        unfocusedBorderColor = secondaryTextColor,
                        focusedContainerColor = Color(0xFF2A2730),
                        unfocusedContainerColor = Color(0xFF2A2730),
                        cursorColor = accentColor2,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp)
                )

                if (isEditing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .size(48.dp)
                            .align(Alignment.CenterHorizontally),
                        color = accentColor1,
                        strokeWidth = 4.dp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (editedPassword.username.isBlank()) {
                        editError = if (language == "fr")
                            "Le nom d'utilisateur est requis"
                        else
                            "Username is required"
                        return@Button
                    }

                    if (editedPassword.password.isBlank()) {
                        editError = if (language == "fr")
                            "Le mot de passe est requis"
                        else
                            "Password is required"
                        return@Button
                    }

                    isEditing = true
                    editError = null

                    coroutineScope.launch {
                        try {
                            val clientId = clientEx.id.id ?: throw Exception(
                                if (language == "fr")
                                    "ID client invalide"
                                else
                                    "Invalid client ID"
                            )

                            val result = updatePass(
                                token = token,
                                uuid = clientId,
                                passUuid = uuid,
                                pass = editedPassword,
                                client = clientEx.c
                            )

                            result.fold(
                                onSuccess = {
                                    onEditSuccess(editedPassword)

                                    isEditing = false

                                    Toast.makeText(
                                        context,
                                        if (language == "fr")
                                            "Mot de passe modifié avec succès"
                                        else
                                            "Password updated successfully",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onFailure = { e ->
                                    isEditing = false
                                    editError = e.message ?: if (language == "fr")
                                        "Erreur lors de la modification"
                                    else
                                        "Error updating password"
                                }
                            )
                        } catch (e: Exception) {
                            isEditing = false
                            editError = e.message ?: if (language == "fr")
                                "Erreur lors de la modification"
                            else
                                "Error updating password"
                        }
                    }
                },
                enabled = !isEditing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor2,
                    contentColor = Color(0xFF1D1B21),
                    disabledContainerColor = accentColor2.copy(alpha = 0.5f),
                    disabledContentColor = Color(0xFF1D1B21).copy(alpha = 0.5f)
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (language == "fr") "Confirmer" else "Save",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3740),
                    contentColor = Color.White
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (language == "fr") "Annuler" else "Cancel",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        containerColor = backgroundColor,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(8.dp)
    )

    // Ajouter le dialogue de génération de mot de passe
    if (showPasswordGenerator) {
        PasswordGeneratorDialog(
            language = language,
            base = password.password,
            onDismiss = { showPasswordGenerator = false },
            onPasswordGenerated = { generatedPassword ->
                editedPassword = editedPassword.copy(password = generatedPassword)
            }
        )
    }
}

@Composable
fun DeletePasswordDialog(
    password: Password,
    language: String,
    clientEx: ClientEx,
    token: String,
    uuid: Uuid,
    onDismiss: () -> Unit,
    onDeleteSuccess: () -> Unit
) {
    var isDeleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (language == "fr") "Supprimer le mot de passe" else "Delete password",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                if (deleteError != null) {
                    Text(
                        text = deleteError!!,
                        color = errorColor,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .background(errorColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .fillMaxWidth()
                    )
                }

                Text(
                    text = if (language == "fr")
                        "Êtes-vous sûr de vouloir supprimer ce mot de passe ?"
                    else
                        "Are you sure you want to delete this password?",
                    fontSize = 16.sp,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Informations sur le mot de passe
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A2730), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = password.url ?: password.app_id ?: (if (language == "fr") "Service non spécifié" else "Unspecified service"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = password.username,
                        fontSize = 14.sp,
                        color = secondaryTextColor
                    )
                }

                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .size(48.dp)
                            .align(Alignment.CenterHorizontally),
                        color = accentColor1,
                        strokeWidth = 4.dp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isDeleting = true
                    deleteError = null

                    coroutineScope.launch {
                        try {
                            val clientId = clientEx.id.id ?: throw Exception(
                                if (language == "fr")
                                    "ID client invalide"
                                else
                                    "Invalid client ID"
                            )

                            deletePass(token, clientId, uuid)?.let { e ->
                                throw Exception(e.message)
                            }

                            onDeleteSuccess()

                            Toast.makeText(
                                context,
                                if (language == "fr")
                                    "Mot de passe supprimé"
                                else
                                    "Password deleted",
                                Toast.LENGTH_SHORT
                            ).show()

                        } catch (e: Exception) {
                            isDeleting = false
                            deleteError = e.message ?: if (language == "fr")
                                "Erreur lors de la suppression"
                            else
                                "Error deleting password"
                        }
                    }
                },
                enabled = !isDeleting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = errorColor,
                    contentColor = Color.White,
                    disabledContainerColor = errorColor.copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f)
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (language == "fr") "Supprimer" else "Delete",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3740),
                    contentColor = Color.White
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (language == "fr") "Annuler" else "Cancel",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        containerColor = backgroundColor,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(8.dp)
    )
}

@Composable
fun SharePasswordDialog(
    password: Password,
    uuid: Uuid,
    sharedEmails: List<String>,
    sharedStatuses: List<ShareStatus>,
    language: String,
    clientEx: ClientEx,
    token: String,
    onDismiss: () -> Unit,
    onShareSuccess: (String) -> Unit,
    onUnshareSuccess: (String) -> Unit
) {
    var recipientEmail by remember { mutableStateOf("") }
    var isSharing by remember { mutableStateOf(false) }
    var isUnsharing by remember { mutableStateOf(false) }
    var unsharingEmail by remember { mutableStateOf("") }
    var shareError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (language == "fr") "Partager le mot de passe" else "Share password",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                if (shareError != null) {
                    Text(
                        text = shareError!!,
                        color = errorColor,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .background(errorColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = recipientEmail,
                    onValueChange = { recipientEmail = it },
                    label = {
                        Text(
                            text = if (language == "fr") "Email du destinataire" else "Recipient email",
                            fontSize = 16.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor2,
                        unfocusedBorderColor = secondaryTextColor,
                        focusedContainerColor = Color(0xFF2A2730),
                        unfocusedContainerColor = Color(0xFF2A2730),
                        cursorColor = accentColor2,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp)
                )

                if (isSharing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .size(48.dp)
                            .align(Alignment.CenterHorizontally),
                        color = accentColor1,
                        strokeWidth = 4.dp
                    )
                }

                // Afficher la liste des emails partagés
                if (sharedEmails.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = if (language == "fr") "Déjà partagé avec :" else "Already shared with:",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        sharedEmails.forEachIndexed { index, email ->
                            val status = if (index < sharedStatuses.size) sharedStatuses[index] else ShareStatus.Pending
                            val statusText = when (status) {
                                ShareStatus.Pending -> if (language == "fr") "En attente" else "Pending"
                                ShareStatus.Accepted -> if (language == "fr") "Accepté" else "Accepted"
                                ShareStatus.Rejected -> if (language == "fr") "Rejeté" else "Rejected"
                            }
                            val statusColor = when (status) {
                                ShareStatus.Pending -> Color(0xFFFFA500) // Orange
                                ShareStatus.Accepted -> Color(0xFF4CAF50) // Vert
                                ShareStatus.Rejected -> errorColor // Rouge
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2A2730), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = email,
                                        fontSize = 16.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = statusText,
                                        fontSize = 14.sp,
                                        color = statusColor,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                // Bouton pour annuler le partage
                                if (isUnsharing && unsharingEmail == email) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        color = accentColor1,
                                        strokeWidth = 3.dp
                                    )
                                } else {
                                    IconButton(
                                        onClick = {
                                            // Annuler le partage
                                            unsharingEmail = email
                                            isUnsharing = true

                                            coroutineScope.launch {
                                                try {
                                                    unsharePassword(
                                                        clientEx = clientEx,
                                                        token = token,
                                                        passUuid = uuid,
                                                        recipientEmail = email,
                                                        onSuccess = {
                                                            onUnshareSuccess(email)

                                                            Toast.makeText(
                                                                context,
                                                                if (language == "fr")
                                                                    "Partage annulé avec $email"
                                                                else
                                                                    "Sharing canceled with $email",
                                                                Toast.LENGTH_SHORT
                                                            ).show()

                                                            isUnsharing = false
                                                            unsharingEmail = ""
                                                        },
                                                        onError = { errorMsg ->
                                                            Toast.makeText(
                                                                context,
                                                                if (language == "fr")
                                                                    "Erreur: $errorMsg"
                                                                else
                                                                    "Error: $errorMsg",
                                                                Toast.LENGTH_SHORT
                                                            ).show()

                                                            isUnsharing = false
                                                            unsharingEmail = ""
                                                        }
                                                    )
                                                } catch (e: Exception) {
                                                    Toast.makeText(
                                                        context,
                                                        if (language == "fr")
                                                            "Erreur: ${e.message}"
                                                        else
                                                            "Error: ${e.message}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()

                                                    isUnsharing = false
                                                    unsharingEmail = ""
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(errorColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                            .padding(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = if (language == "fr") "Annuler le partage" else "Cancel sharing",
                                            tint = errorColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (recipientEmail.isBlank()) {
                        shareError = if (language == "fr")
                            "Veuillez saisir l'email du destinataire"
                        else
                            "Please enter recipient email"
                        return@Button
                    }

                    isSharing = true
                    shareError = null

                    coroutineScope.launch {
                        try {
                            val clientId = clientEx.id.id ?: throw Exception(
                                if (language == "fr")
                                    "ID client invalide"
                                else
                                    "Invalid client ID"
                            )

                            val result = sharePass(
                                token = token,
                                ownerUuid = clientId,
                                passUuid = uuid,
                                recipientEmail = recipientEmail,
                                client = clientEx.c,
                                password = password
                            )

                            result.fold(
                                onSuccess = {
                                    onShareSuccess(recipientEmail)

                                    isSharing = false
                                    recipientEmail = ""

                                    Toast.makeText(
                                        context,
                                        if (language == "fr")
                                            "Mot de passe partagé avec succès"
                                        else
                                            "Password shared successfully",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onFailure = { e ->
                                    isSharing = false
                                    shareError = e.message ?: if (language == "fr")
                                        "Erreur lors du partage"
                                    else
                                        "Error sharing password"
                                }
                            )
                        } catch (e: Exception) {
                            isSharing = false
                            shareError = e.message ?: if (language == "fr")
                                "Erreur lors du partage"
                            else
                                "Error sharing password"
                        }
                    }
                },
                enabled = !isSharing && !isUnsharing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor2,
                    contentColor = Color(0xFF1D1B21),
                    disabledContainerColor = accentColor2.copy(alpha = 0.5f),
                    disabledContentColor = Color(0xFF1D1B21).copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .height(48.dp)
            ) {
                Text(
                    text = if (language == "fr") "Partager" else "Share",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3740),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .height(48.dp)
            ) {
                Text(
                    text = if (language == "fr") "Fermer" else "Close",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        containerColor = backgroundColor,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(8.dp)
    )
}

@Composable
fun AddPasswordDialog(
    language: String,
    clientEx: ClientEx,
    token: String,
    onDismiss: () -> Unit,
    onAddSuccess: (Password, Uuid) -> Unit
) {
    var newPassword by remember { mutableStateOf(Password("", null, "", null, null, null)) }
    var isAdding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var urlType by remember { mutableStateOf(true) } // true pour URL, false pour App ID
    var showPasswordGenerator by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (language == "fr") "Ajouter un mot de passe" else "Add password",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                if (addError != null) {
                    Text(
                        text = addError!!,
                        color = errorColor,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .background(errorColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .fillMaxWidth()
                    )
                }

                // Type de service (URL ou App ID)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { urlType = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (urlType) accentColor2 else Color(0xFF3A3740),
                            contentColor = if (urlType) backgroundColor else Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("URL",  color = if (urlType) backgroundColor else Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { urlType = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!urlType) accentColor2 else Color(0xFF3A3740),
                            contentColor = if (!urlType) backgroundColor else Color.White

                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("App ID", color = if (!urlType) backgroundColor else Color.White )
                    }
                }

                // URL ou App ID
                OutlinedTextField(
                    value = if (urlType) newPassword.url ?: "" else newPassword.app_id ?: "",
                    onValueChange = {
                        newPassword = if (urlType) {
                            newPassword.copy(url = it, app_id = null)
                        } else {
                            newPassword.copy(url = null, app_id = it)
                        }
                    },
                    label = {
                        Text(
                            text = if (urlType) "URL" else "App ID",
                            fontSize = 16.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .padding(bottom = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor2,
                        unfocusedBorderColor = secondaryTextColor,
                        focusedContainerColor = Color(0xFF2A2730),
                        unfocusedContainerColor = Color(0xFF2A2730),
                        cursorColor = accentColor2,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp)
                )

                // Username
                OutlinedTextField(
                    value = newPassword.username,
                    onValueChange = { newPassword = newPassword.copy(username = it) },
                    label = {
                        Text(
                            text = if (language == "fr") "Nom d'utilisateur" else "Username",
                            fontSize = 16.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .padding(bottom = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor2,
                        unfocusedBorderColor = secondaryTextColor,
                        focusedContainerColor = Color(0xFF2A2730),
                        unfocusedContainerColor = Color(0xFF2A2730),
                        cursorColor = accentColor2,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp)
                )

                // Password
                OutlinedTextField(
                    value = newPassword.password,
                    onValueChange = { newPassword = newPassword.copy(password = it) },
                    label = {
                        Text(
                            text = if (language == "fr") "Mot de passe" else "Password",
                            fontSize = 16.sp
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPasswordGenerator = true }) {
                            Icon(
                                imageVector = Icons.Default.Casino,
                                contentDescription = if (language == "fr") "Générer" else "Generate",
                                tint = accentColor2
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .padding(bottom = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor2,
                        unfocusedBorderColor = secondaryTextColor,
                        focusedContainerColor = Color(0xFF2A2730),
                        unfocusedContainerColor = Color(0xFF2A2730),
                        cursorColor = accentColor2,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp)
                )

                // OTP (optionnel)
                OutlinedTextField(
                    value = newPassword.otp ?: "",
                    onValueChange = { newPassword = newPassword.copy(otp = if (it.isBlank()) null else it) },
                    label = {
                        Text(
                            text = if (language == "fr") "2FA (optionnel)" else "2FA (optional)",
                            fontSize = 16.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .padding(bottom = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor2,
                        unfocusedBorderColor = secondaryTextColor,
                        focusedContainerColor = Color(0xFF2A2730),
                        unfocusedContainerColor = Color(0xFF2A2730),
                        cursorColor = accentColor2,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp)
                )

                if (isAdding) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .size(48.dp)
                            .align(Alignment.CenterHorizontally),
                        color = accentColor1,
                        strokeWidth = 4.dp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newPassword.username.isBlank()) {
                        addError = if (language == "fr")
                            "Le nom d'utilisateur est requis"
                        else
                            "Username is required"
                        return@Button
                    }

                    if (newPassword.password.isBlank()) {
                        addError = if (language == "fr")
                            "Le mot de passe est requis"
                        else
                            "Password is required"
                        return@Button
                    }

                    if (urlType && (newPassword.url?.isBlank() == true)) {
                        addError = if (language == "fr")
                            "L'URL est requise"
                        else
                            "URL is required"
                        return@Button
                    }

                    if (!urlType && (newPassword.app_id?.isBlank() == true)) {
                        addError = if (language == "fr")
                            "L'App ID est requis"
                        else
                            "App ID is required"
                        return@Button
                    }

                    isAdding = true
                    addError = null

                    coroutineScope.launch {
                        try {
                            val clientId = clientEx.id.id ?: throw Exception(
                                if (language == "fr")
                                    "ID client invalide"
                                else
                                    "Invalid client ID"
                            )

                            val result = createPass(
                                token = token,
                                uuid = clientId,
                                pass = newPassword,
                                client = clientEx.c
                            )

                            result.fold(
                                onSuccess = { newUuid ->
                                    val uuid4 = toUuid(newUuid.replace("\"",""))
                                    onAddSuccess(newPassword, uuid4)

                                    isAdding = false

                                    Toast.makeText(
                                        context,
                                        if (language == "fr")
                                            "Mot de passe ajouté avec succès"
                                        else
                                            "Password added successfully",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onFailure = { e ->
                                    isAdding = false
                                    addError = e.message ?: if (language == "fr")
                                        "Erreur lors de l'ajout"
                                    else
                                        "Error adding password"
                                }
                            )
                        } catch (e: Exception) {
                            isAdding = false
                            addError = e.message ?: if (language == "fr")
                                "Erreur lors de l'ajout"
                            else
                                "Error adding password"
                        }
                    }
                },
                enabled = !isAdding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor2,
                    contentColor = Color(0xFF1D1B21),
                    disabledContainerColor = accentColor2.copy(alpha = 0.5f),
                    disabledContentColor = Color(0xFF1D1B21).copy(alpha = 0.5f)
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (language == "fr") "Ajouter" else "Add",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3740),
                    contentColor = Color.White
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (language == "fr") "Annuler" else "Cancel",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        containerColor = backgroundColor,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(8.dp)
    )

    // Ajouter le dialogue de génération de mot de passe
    if (showPasswordGenerator) {
        PasswordGeneratorDialog(
            language = language,
            onDismiss = { showPasswordGenerator = false },
            base = null,
            onPasswordGenerated = { generatedPassword ->
                newPassword = newPassword.copy(password = generatedPassword)
            }
        )
    }
}

// Fonction utilitaire pour mettre à jour le code OTP
private suspend fun updateOtpCode(
    params: Otp,
    periodSeconds: Int,
    onUpdate: (String, Int) -> Unit
) {
    try {
        while (true) {
            try {
                // Calculer le délai jusqu'à la prochaine seconde
                val currentTimeMillis = System.currentTimeMillis()
                val delayToNextSecond = 1000 - (currentTimeMillis % 1000)

                // Attendre jusqu'à la prochaine seconde exacte
                delay(delayToNextSecond)

                // Vérifier si la coroutine a été annulée
                currentCoroutineContext().ensureActive()

                // Calculer le temps actuel en secondes
                val currentTimeSec = System.currentTimeMillis() / 1000

                // Calculer le début du cycle TOTP actuel (temps normalisé par la période)
                val currentTimeBlock = currentTimeSec / periodSeconds
                val totpStartTime = currentTimeBlock * periodSeconds

                // Calculer le temps restant jusqu'à la fin du cycle actuel
                val newRemainingSeconds = (totpStartTime + periodSeconds) - currentTimeSec

                // Générer le code OTP actuel
                val newCode = OtpUtils.generate(params)
                onUpdate(newCode, newRemainingSeconds.toInt())
            } catch (e: CancellationException) {
                // Propager l'exception d'annulation pour terminer proprement la coroutine
                throw e
            } catch (e: Exception) {
                Log.e("OTP", "Erreur lors de la mise à jour du code OTP: ${e.message}")
                delay(1000) // En cas d'erreur, attendre une seconde avant de réessayer
            }
        }
    } catch (e: CancellationException) {
        // Gérer l'annulation silencieusement sans log d'erreur
        Log.d("OTP", "Mise à jour OTP annulée normalement")
    } catch (e: Exception) {
        // Log uniquement les erreurs non liées à l'annulation
        Log.e("OTP", "Erreur fatale dans updateOtpCode: ${e.message}")
    }
}

// Fonction pour charger les mots de passe
private fun loadPasswords(
    clientEx: ClientEx,
    token: String,
    coroutineScope: CoroutineScope,
    onError: (String) -> Unit,
    onSuccess: (Pair<List<Pair<Password, Uuid>>, List<Quadruple<Password, Uuid, Uuid, ShareStatus>>>) -> Unit
) {
    coroutineScope.launch {
        try {
            val uuid = clientEx.id.id ?: throw Exception(
                if (Locale.getDefault().language.startsWith("fr"))
                    "ID client invalide"
                else
                    "Invalid client ID"
            )

            val result = getAll(token, uuid, clientEx.c)

            result.fold(
                onSuccess = { passwords ->
                    onSuccess(Pair(passwords.passwords.toList(), passwords.sharedPasswords.toList()))
                },
                onFailure = { e ->
                    onError(e.message ?: if (Locale.getDefault().language.startsWith("fr"))
                        "Erreur lors du chargement des mots de passe"
                    else
                        "Error loading passwords")
                }
            )
        } catch (e: Exception) {
            onError(e.message ?: if (Locale.getDefault().language.startsWith("fr"))
                "Erreur lors du chargement des mots de passe"
            else
                "Error loading passwords")
        }
    }
}

// Fonction pour charger les informations de partage
private fun loadSharedInfo(
    clientEx: ClientEx,
    token: String,
    coroutineScope: CoroutineScope,
    onSuccess: (Map<String, List<String>>, Map<String, List<ShareStatus>>) -> Unit
) {
    coroutineScope.launch {
        try {
            Log.i(null, clientEx.toString())
            Log.i(null, token.toString())
            val uuid = clientEx.id.id ?: throw Exception(
                if (Locale.getDefault().language.startsWith("fr"))
                    "ID client invalide"
                else
                    "Invalid client ID"
            )

            val result = getSharedByUserEmails(token,uuid)

            result.fold(
                onSuccess = { sharedInfo ->
                    val sharedPasswords = mutableMapOf<String, List<String>>()
                    val sharedStatuses = mutableMapOf<String, List<ShareStatus>>()

                    sharedInfo.forEach { shared ->
                        val passUuidStr = shared.passId
                        sharedPasswords[passUuidStr] = shared.emails
                        shared.statuses?.let { statuses ->
                            sharedStatuses[passUuidStr] = statuses
                        } ?: run {
                            // Si les statuts ne sont pas disponibles, utiliser Pending par défaut
                            sharedStatuses[passUuidStr] = List(shared.emails.size) { ShareStatus.Pending }
                        }
                    }

                    onSuccess(sharedPasswords, sharedStatuses)
                },
                onFailure = { e ->
                    Log.e("SharedInfo", "Erreur lors du chargement des informations de partage: ${e.message}")
                }
            )
        } catch (e: Exception) {
            Log.e("SharedInfo", "Erreur lors du chargement des informations de partage: ${e.message}")
        }
    }
}

// Ajouter cette fonction après la fonction loadPasswords
private suspend fun deletePassword(
    clientEx: ClientEx,
    token: String,
    uuid: Uuid,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    try {
        val clientId = clientEx.id.id ?: throw Exception(
            if (Locale.getDefault().language.startsWith("fr"))
                "ID client invalide"
            else
                "Invalid client ID"
        )

        val exception = deletePass(token, clientId, uuid)

        if (exception == null) {
            onSuccess()
        } else {
            onError(exception.message ?: if (Locale.getDefault().language.startsWith("fr"))
                "Erreur lors de la suppression du mot de passe"
            else
                "Error deleting password")
        }
    } catch (e: Exception) {
        onError(e.message ?: if (Locale.getDefault().language.startsWith("fr"))
            "Erreur lors de la suppression du mot de passe"
        else
            "Error deleting password")
    }
}

// Fonction pour annuler le partage d'un mot de passe
private suspend fun unsharePassword(
    clientEx: ClientEx,
    token: String,
    passUuid: Uuid,
    recipientEmail: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    try {
        val clientId = clientEx.id.id ?: throw Exception(
            if (Locale.getDefault().language.startsWith("fr"))
                "ID client invalide"
            else
                "Invalid client ID"
        )

        // Récupérer l'UUID du destinataire à partir de son email
        val recipientUuidResult = getUuidFromEmail(recipientEmail)
        if (recipientUuidResult.isFailure) {
            onError(if (Locale.getDefault().language.startsWith("fr"))
                "Utilisateur non trouvé: $recipientEmail"
            else
                "User not found: $recipientEmail")
            return
        }

        val recipientUuidStr = recipientUuidResult.getOrNull()!!
        val recipientUuid = createUuid(recipientUuidStr)

        // Appeler la fonction d'annulation de partage
        val result = unsharePass(token, clientId, passUuid, recipientUuid)

        result.fold(
            onSuccess = { _ ->
                onSuccess()
            },
            onFailure = { e ->
                onError(e.message ?: if (Locale.getDefault().language.startsWith("fr"))
                    "Erreur lors de l'annulation du partage"
                else
                    "Error unsharing password")
            }
        )
    } catch (e: Exception) {
        onError(e.message ?: if (Locale.getDefault().language.startsWith("fr"))
            "Erreur lors de l'annulation du partage"
        else
            "Error unsharing password")
    }
}

@Preview(showBackground = true)
@Composable
fun VaultScreenPreview() {
    SkapTheme {
        // Créer un ClientEx factice pour la prévisualisation
        val kyP = ByteArray(KY_PUBLIC_KEY_SIZE)
        val kyQ = ByteArray(KY_SECRET_KEY_SIZE)
        val diP = ByteArray(DI_PUBLIC_KEY_SIZE)
        val diQ = ByteArray(DI_SECRET_KEY_SIZE)
        val secret = ByteArray(32)

        val client = Client(kyP, kyQ, diP, diQ, secret)
        val id = CK("user@example.com", Uuid(ByteArray(16)), kyP, diP)
        val clientEx = ClientEx(client, id)

        VaultScreen(
            clientEx = clientEx,
            token = "fake-token",
            onLogout = {}
        )
    }
}

@Composable
fun PasswordGeneratorDialog(
    language: String,
    base: String?,
    onDismiss: () -> Unit,
    onPasswordGenerated: (String) -> Unit
) {
    var length by remember { mutableStateOf(16) }
    var includeUppercase by remember { mutableStateOf(true) }
    var includeLowercase by remember { mutableStateOf(true) }
    var includeNumbers by remember { mutableStateOf(true) }
    var includeSpecialChars by remember { mutableStateOf(true) }
    var generatedPassword by remember { mutableStateOf("") }
    var passwordStrength by remember { mutableStateOf<Double>(0.0) }
    
    // Nouveaux états pour l'audit de sécurité
    var isSecurityAuditVisible by remember { mutableStateOf(false) }
    var isAuditLoading by remember { mutableStateOf(false) }
    var auditResult by remember { mutableStateOf<PasswordAuditResult?>(null) }
    
    val coroutineScope = rememberCoroutineScope()
    val securityAuditor = remember { PasswordSecurityAuditor() }

    fun generateNewPassword(first:Boolean) {
        if (first and (base != null)) {
            generatedPassword = base!!

            // Calculer la force du mot de passe
            val passwordStrengthEstimator = me.gosimple.nbvcxz.Nbvcxz()
            val result = passwordStrengthEstimator.estimate(base)
            passwordStrength = result.entropy / 128.0 // Normaliser sur une échelle de 0 à 1
        } else {
            val charset = StringBuilder()
            if (includeUppercase) charset.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            if (includeLowercase) charset.append("abcdefghijklmnopqrstuvwxyz")
            if (includeNumbers) charset.append("0123456789")
            if (includeSpecialChars) charset.append("!@#$%^&*()_+-=[]{}|;:,.<>?")

            if (charset.isEmpty()) {
                includeLowercase = true
                charset.append("abcdefghijklmnopqrstuvwxyz")
            }

            val random = java.security.SecureRandom()
            val password = (1..length)
                .map { charset[random.nextInt(charset.length)] }
                .joinToString("")
            generatedPassword = password

            // Calculer la force du mot de passe
            val passwordStrengthEstimator = me.gosimple.nbvcxz.Nbvcxz()
            val result = passwordStrengthEstimator.estimate(password)
            passwordStrength = result.entropy / 128.0 // Normaliser sur une échelle de 0 à 1
        }
        
        // Réinitialiser l'audit de sécurité lorsqu'un nouveau mot de passe est généré
        isSecurityAuditVisible = false
        auditResult = null
    }
    
    fun runSecurityAudit() {
        isAuditLoading = true
        isSecurityAuditVisible = true
        
        coroutineScope.launch {
            auditResult = securityAuditor.auditPassword(generatedPassword, language)
            isAuditLoading = false
        }
    }

    LaunchedEffect(Unit) {
        generateNewPassword(true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (language == "fr") "Générer un mot de passe" else "Generate password",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                // Mot de passe généré
                OutlinedTextField(
                    value = generatedPassword,
                    onValueChange = { /* Lecture seule */ },
                    readOnly = true,
                    label = {
                        Text(
                            text = if (language == "fr") "Mot de passe généré" else "Generated password",
                            fontSize = 16.sp
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { generateNewPassword(false) }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = if (language == "fr") "Régénérer" else "Regenerate",
                                tint = accentColor2
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor2,
                        unfocusedBorderColor = secondaryTextColor,
                        focusedContainerColor = Color(0xFF2A2730),
                        unfocusedContainerColor = Color(0xFF2A2730),
                        cursorColor = accentColor2,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                // Indicateur de force
                Text(
                    text = if (language == "fr") "Force du mot de passe:" else "Password strength:",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                LinearProgressIndicator(
                    progress = passwordStrength.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        passwordStrength < 0.3 -> errorColor
                        passwordStrength < 0.6 -> Color(0xFFFFA500)
                        else -> Color(0xFF4CAF50)
                    }
                )

                Text(
                    text = when {
                        passwordStrength < 0.3 -> if (language == "fr") "Faible" else "Weak"
                        passwordStrength < 0.6 -> if (language == "fr") "Moyen" else "Medium"
                        else -> if (language == "fr") "Fort" else "Strong"
                    },
                    color = when {
                        passwordStrength < 0.3 -> errorColor
                        passwordStrength < 0.6 -> Color(0xFFFFA500)
                        else -> Color(0xFF4CAF50)
                    },
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )
                
                // Bouton pour lancer l'audit de sécurité
                if (!isSecurityAuditVisible) {
                    Button(
                        onClick = { runSecurityAudit() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3A3740),
                            contentColor = Color.White
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = if (language == "fr") "Vérifier la sécurité" else "Check security",
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                
                // Résultats de l'audit de sécurité
                if (isSecurityAuditVisible) {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Titre
                    Text(
                        text = if (language == "fr") "Audit de sécurité:" else "Security audit:",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (isAuditLoading) {
                        // Indicateur de chargement
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = accentColor2,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    } else if (auditResult != null) {
                        // Status de compromission
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (auditResult!!.isCompromised) 
                                    Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (auditResult!!.isCompromised) errorColor else Color(0xFF4CAF50),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = auditResult!!.securityMessage,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // Longueur
                Text(
                    text = if (language == "fr") "Longueur: $length" else "Length: $length",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )

                Slider(
                    value = length.toFloat(),
                    onValueChange = { length = it.toInt(); generateNewPassword(false) },
                    valueRange = 8f..32f,
                    steps = 23,
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor2,
                        activeTrackColor = accentColor2,
                        inactiveTrackColor = Color(0xFF3A3740)
                    ),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // Options
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (language == "fr") "Majuscules (A-Z)" else "Uppercase (A-Z)",
                            color = Color.White
                        )
                        Switch(
                            checked = includeUppercase,
                            onCheckedChange = { includeUppercase = it; generateNewPassword(false) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentColor2,
                                checkedTrackColor = accentColor2.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
                            )
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (language == "fr") "Minuscules (a-z)" else "Lowercase (a-z)",
                            color = Color.White
                        )
                        Switch(
                            checked = includeLowercase,
                            onCheckedChange = { includeLowercase = it; generateNewPassword(false) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentColor2,
                                checkedTrackColor = accentColor2.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
                            )
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (language == "fr") "Chiffres (0-9)" else "Numbers (0-9)",
                            color = Color.White
                        )
                        Switch(
                            checked = includeNumbers,
                            onCheckedChange = { includeNumbers = it; generateNewPassword(false) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentColor2,
                                checkedTrackColor = accentColor2.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
                            )
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (language == "fr") "Caractères spéciaux" else "Special characters",
                            color = Color.White
                        )
                        Switch(
                            checked = includeSpecialChars,
                            onCheckedChange = { includeSpecialChars = it; generateNewPassword(false) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentColor2,
                                checkedTrackColor = accentColor2.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onPasswordGenerated(generatedPassword)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor2,
                    contentColor = Color(0xFF1D1B21)
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (language == "fr") "Utiliser" else "Use",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3740),
                    contentColor = Color.White
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (language == "fr") "Annuler" else "Cancel",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        containerColor = backgroundColor,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(8.dp)
    )
}

@Composable
fun RejectedPasswordsDialog(
    language: String,
    clientEx: ClientEx,
    token: String,
    pendingSharedPasswords: List<Quadruple<Password, Uuid, Uuid, ShareStatus>>,
    onDismiss: () -> Unit,
    onAcceptAgain: (Uuid, Uuid, Password) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (language == "fr") "Mots de passe rejetés" else "Rejected passwords",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                if (pendingSharedPasswords.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (language == "fr") 
                                "Aucun mot de passe rejeté"
                            else 
                                "No rejected passwords",
                            fontSize = 16.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text(
                        text = if (language == "fr")
                            "Vous pouvez accepter à nouveau ces mots de passe partagés que vous avez précédemment rejetés."
                        else
                            "You can accept again these shared passwords that you previously rejected.",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 300.dp)
                    ) {
                        items(pendingSharedPasswords) { (password, passUuid, ownerUuid, _) ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF2A2730)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // Icône pour l'URL ou l'App ID
                                        if (password.url != null) {
                                            val domain = try {
                                                Uri.parse(password.url).host ?: password.url
                                            } catch (e: Exception) {
                                                password.url
                                            }
                                            
                                            Box(
                                                modifier = Modifier.size(36.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                AsyncImage(
                                                    model = ImageRequest.Builder(LocalContext.current)
                                                        .data("https://www.google.com/s2/favicons?domain=$domain&sz=64")
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Fit,
                                                    placeholder = androidx.compose.ui.graphics.vector.rememberVectorPainter(image = Icons.Default.Public),
                                                    error = androidx.compose.ui.graphics.vector.rememberVectorPainter(image = Icons.Default.Public),
                                                    modifier = Modifier.size(36.dp)
                                                )
                                            }
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Apps,
                                                contentDescription = null,
                                                tint = accentColor1,
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.width(12.dp))
                                        
                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = password.url ?: password.app_id ?: (if (language == "fr") "Service non spécifié" else "Unspecified service"),
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 16.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            
                                            Text(
                                                text = password.username,
                                                color = secondaryTextColor,
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.width(8.dp))
                                        
                                        // Bouton pour accepter à nouveau
                                        Button(
                                            onClick = { onAcceptAgain(passUuid, ownerUuid, password) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = accentColor2
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            Text(
                                                text = if (language == "fr") "Accepter" else "Accept",
                                                color = backgroundColor,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor1
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (language == "fr") "Fermer" else "Close",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        containerColor = backgroundColor,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun SecurityAuditDialog(
    language: String,
    passwords: Map<String, String>,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val securityAuditor = remember { PasswordSecurityAuditor() }
    var isAuditLoading by remember { mutableStateOf(true) }
    var auditResult by remember { mutableStateOf<GlobalSecurityAuditResult?>(null) }
    var selectedPasswordId by remember { mutableStateOf<String?>(null) }
    
    // Lance l'audit de sécurité au démarrage du dialogue
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            auditResult = securityAuditor.auditAllPasswords(passwords, language)
            isAuditLoading = false
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (language == "fr") "Audit de sécurité" else "Security Audit",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (isAuditLoading) {
                    // Indicateur de chargement
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = accentColor2,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (language == "fr") "Analyse de vos mots de passe..." 
                                      else "Analyzing your passwords...",
                                color = Color.White,
                                fontSize = 16.sp
                            )
                        }
                    }
                } else if (auditResult != null) {
                    // Résumé global
                    Text(
                        text = if (language == "fr") "Score global de sécurité:" else "Overall security score:",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    // Barre de progression avec le score global
                    Box(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = auditResult!!.overallScore.toFloat(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            color = when {
                                auditResult!!.overallScore < 0.3 -> errorColor
                                auditResult!!.overallScore < 0.6 -> Color(0xFFFFA500)
                                else -> Color(0xFF4CAF50)
                            }
                        )
                        
                        // Texte au-dessus de la barre de progression
                        Text(
                            text = "${(auditResult!!.overallScore * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.Center)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Statistiques
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF2A2730)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth()
                        ) {
                            Text(
                                text = if (language == "fr") "Statistiques" else "Statistics",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                SecurityStatItem(
                                    label = if (language == "fr") "Mots de passe forts" else "Strong passwords",
                                    value = auditResult!!.strongCount,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                SecurityStatItem(
                                    label = if (language == "fr") "Mots de passe moyens" else "Medium passwords",
                                    value = auditResult!!.mediumCount,
                                    color = Color(0xFFFFA500)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                SecurityStatItem(
                                    label = if (language == "fr") "Mots de passe faibles" else "Weak passwords",
                                    value = auditResult!!.weakCount,
                                    color = errorColor
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                SecurityStatItem(
                                    label = if (language == "fr") "Mots de passe compromis" else "Compromised passwords",
                                    value = auditResult!!.compromisedCount,
                                    color = errorColor
                                )
                            }
                            
                            if (auditResult!!.duplicateCount > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    SecurityStatItem(
                                        label = if (language == "fr") "Mots de passe dupliqués" else "Duplicate passwords",
                                        value = auditResult!!.duplicateCount,
                                        color = errorColor
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Liste des mots de passe problématiques
                    val problematicPasswords = auditResult!!.passwordResults.filter { (_, result) ->
                        result.isCompromised || result.strength < 0.3
                    }
                    
                    if (problematicPasswords.isNotEmpty()) {
                        Text(
                            text = if (language == "fr") "Mots de passe à risque:" else "Risky passwords:",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        problematicPasswords.forEach { (id, result) ->
                            val password = passwords[id] ?: return@forEach
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { 
                                        selectedPasswordId = if (selectedPasswordId == id) null else id
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF2A2730)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (result.isCompromised) 
                                                    Icons.Default.Warning else Icons.Default.Security,
                                                contentDescription = null,
                                                tint = if (result.isCompromised) errorColor 
                                                      else if (result.strength < 0.3) Color(0xFFFFA500)
                                                      else accentColor2,
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .padding(end = 8.dp)
                                            )
                                            Text(
                                                text = if (password.length > 12) 
                                                    password.take(5) + "..." + password.takeLast(5)
                                                else password,
                                                color = Color.White,
                                                fontSize = 16.sp
                                            )
                                        }
                                        
                                        Text(
                                            text = result.strengthMessage,
                                            color = when {
                                                result.strength < 0.3 -> errorColor
                                                result.strength < 0.6 -> Color(0xFFFFA500)
                                                else -> Color(0xFF4CAF50)
                                            },
                                            fontSize = 14.sp
                                        )
                                    }
                                    
                                    if (selectedPasswordId == id) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = result.securityMessage,
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 14.sp
                                        )
                                        
                                        if (result.isCompromised || result.strength < 0.3) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = if (language == "fr") 
                                                    "Suggestion: Changez ce mot de passe dès que possible."
                                                else 
                                                    "Suggestion: Change this password as soon as possible.",
                                                color = Color(0xFFFFA500),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Conseils de sécurité généraux
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF2A2730)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth()
                        ) {
                            Text(
                                text = if (language == "fr") "Conseils de sécurité" else "Security Tips",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            
                            val tips = if (language == "fr") listOf(
                                "Utilisez des mots de passe uniques pour chaque compte",
                                "Privilégiez les mots de passe longs (au moins 12 caractères)",
                                "Combinez lettres, chiffres et caractères spéciaux",
                                "Évitez les informations personnelles faciles à deviner",
                                "Activez l'authentification à deux facteurs quand c'est possible"
                            ) else listOf(
                                "Use unique passwords for each account",
                                "Prioritize long passwords (at least 12 characters)",
                                "Combine letters, numbers, and special characters",
                                "Avoid easily guessable personal information",
                                "Enable two-factor authentication whenever possible"
                            )
                            
                            tips.forEachIndexed { index, tip ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "${index + 1}.",
                                        color = accentColor2,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(end = 8.dp, top = 2.dp)
                                    )
                                    Text(
                                        text = tip,
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor2,
                    contentColor = Color(0xFF1D1B21)
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (language == "fr") "Fermer" else "Close",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        containerColor = backgroundColor,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .widthIn(max = 500.dp)
    )
}

@Composable
fun SecurityStatItem(
    label: String,
    value: Int,
    color: Color
) {
    Row(
        modifier = Modifier
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(6.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label: $value",
            color = Color.White,
            fontSize = 14.sp
        )
    }
}