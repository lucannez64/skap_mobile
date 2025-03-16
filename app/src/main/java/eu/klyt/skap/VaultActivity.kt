package eu.klyt.skap

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
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    
    // État pour les mots de passe
    var passwords by remember { mutableStateOf<List<Pair<Password, Uuid>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // État pour les partages
    var sharedPasswords by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var sharedPasswordsStatuses by remember { mutableStateOf<Map<String, List<ShareStatus>>>(emptyMap()) }
    
    // État pour le partage actuel
    var currentSharingPassword by remember { mutableStateOf<Password?>(null) }
    var currentSharingUuid by remember { mutableStateOf<Uuid?>(null) }
    var showShareModal by remember { mutableStateOf(false) }
    var currentSharedEmails by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentSharedStatuses by remember { mutableStateOf<List<ShareStatus>>(emptyList()) }
    
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
                passwords = loadedPasswords
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
    val filteredPasswords = remember(passwords, searchQuery) {
        if (searchQuery.isEmpty()) {
            passwords.sortedBy { p -> p.first.url ?: p.first.app_id ?: p.first.username }
        } else {
            passwords.filter { (password, _) ->
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
                            
                            PasswordItem(
                                password = password,
                                uuid = uuid,
                                language = language,
                                clientEx = clientEx,
                                token = token,
                                isShared = isShared,
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
}

@Composable
fun PasswordItem(
    password: Password,
    uuid: Uuid,
    language: String,
    clientEx: ClientEx,
    token: String,
    isShared: Boolean = false,
    onEdit: (Password) -> Unit,
    onDelete: () -> Unit,
    onShare: (Password) -> Unit
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // États pour l'OTP
    var otpCode by remember { mutableStateOf<String?>(null) }
    var remainingSeconds by remember { mutableStateOf(0) }
    var periodSeconds by remember { mutableStateOf(30) }
    
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
            .clickable { showContextMenu = true }
            .background(cardBackgroundColor, RoundedCornerShape(12.dp))
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
                                    .padding(start = 8.dp,end = 8.dp)
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

            // Modifier
            DropdownMenuItem(
                text = { Text(if (language == "fr") "Modifier" else "Edit") },
                onClick = {
                    showEditDialog = true
                    showContextMenu = false
                },
                leadingIcon = { Icon(Icons.Default.Edit, null) }
            )

            // Partager
            DropdownMenuItem(
                text = { Text(if (language == "fr") "Partager" else "Share") },
                onClick = {
                    onShare(password)
                    showContextMenu = false
                },
                leadingIcon = { Icon(Icons.Default.Share, null) }
            )

            // Supprimer
            DropdownMenuItem(
                text = { Text(if (language == "fr") "Supprimer" else "Delete") },
                            onClick = {
                    onDelete()
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
    
    // Dialogue d'édition
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
                    text = if (language == "fr") "Enregistrer" else "Save",
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
    onSuccess: (List<Pair<Password, Uuid>>) -> Unit
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
                    onSuccess(passwords.toList())
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