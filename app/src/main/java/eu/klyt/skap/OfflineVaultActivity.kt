package eu.klyt.skap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Public
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.DpOffset
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.klyt.skap.lib.*
import eu.klyt.skap.ui.theme.SkapTheme
import kotlinx.coroutines.launch
import java.util.*
import dev.medzik.otp.TOTPGenerator
import dev.medzik.otp.OTPParameters
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.coroutineContext

class OfflineVaultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            SkapTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1D1B21)
                ) {
                    OfflineVaultScreen(
                        onBack = {
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineVaultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(getLanguagePreference(context)) }
    
    // État pour les données hors ligne
    val offlineStorageManager = remember { OfflineStorageManager.getInstance(context) }
    var passwords by remember { mutableStateOf<List<Pair<Password, Uuid>>>(emptyList()) }
    var sharedPasswords by remember { mutableStateOf<List<Quadruple<Password, Uuid, Uuid, ShareStatus>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var needsKeyFile by remember { mutableStateOf(true) }
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    
    val coroutineScope = rememberCoroutineScope()
    
    // Sélecteur de fichier de clé
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        selectedFile = uri
        if (uri != null) {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                fileName = cursor.getString(nameIndex)
            }
        }
    }
    
    // Fonction pour charger les données avec le fichier de clé
    fun loadOfflineDataWithKey(keyFileUri: Uri) {
        coroutineScope.launch {
            isLoading = true
            try {
                if (!offlineStorageManager.hasOfflineData()) {
                    error = if (language == "fr") 
                        "Aucune donnée hors ligne disponible"
                    else 
                        "No offline data available"
                    isLoading = false
                    return@launch
                }
                
                // Lire le fichier de clé
                val fileBytes = context.contentResolver.openInputStream(keyFileUri)?.use { 
                    it.readBytes() 
                }
                
                if (fileBytes == null) {
                    error = if (language == "fr")
                        "Impossible de lire le fichier de clé"
                    else
                        "Unable to read key file"
                    isLoading = false
                    return@launch
                }
                
                val clientEx = Decoded.decodeClientEx(fileBytes)
                if (clientEx == null) {
                    error = if (language == "fr")
                        "Le fichier de clé est invalide"
                    else
                        "The key file is invalid"
                    isLoading = false
                    return@launch
                }
                
                // Charger les données hors ligne avec la clé stockée
                val result = offlineStorageManager.loadOfflineData()
                
                result.fold(
                    onSuccess = { (loadedPasswords, loadedSharedPasswords) ->
                        passwords = loadedPasswords
                        sharedPasswords = loadedSharedPasswords
                        needsKeyFile = false
                        
                        Toast.makeText(
                            context,
                            if (language == "fr") 
                                "${loadedPasswords.size} mots de passe chargés"
                            else 
                                "${loadedPasswords.size} passwords loaded",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { e ->
                        error = e.message
                        Log.e("OfflineVault", "Error loading offline data", e)
                    }
                )
                
            } catch (e: Exception) {
                error = e.message
                Log.e("OfflineVault", "Error loading offline data", e)
            } finally {
                isLoading = false
            }
        }
    }
    
    // Vérifier si des données hors ligne existent
    LaunchedEffect(Unit) {
        if (!offlineStorageManager.hasOfflineData()) {
            error = if (language == "fr") 
                "Aucune donnée hors ligne disponible"
            else 
                "No offline data available"
        }
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
        // Back button in top left (replacing profile button position)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 68.dp, start = 16.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .background(secondaryTextColor, RoundedCornerShape(50))
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = if (language == "fr") "Retour" else "Back",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Offline mode indicator in top right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 68.dp, end = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        accentColor1.copy(alpha = 0.2f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = accentColor1,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (language == "fr") "Hors ligne" else "Offline",
                    color = accentColor1,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp, top = 80.dp, end = 16.dp)
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
            
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
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
                                text = if (language == "fr") "Chargement..." else "Loading...",
                                color = Color.White,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
                
                error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = errorColor,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (language == "fr") 
                                    "Erreur lors du chargement des données hors ligne"
                                else 
                                    "Error loading offline data",
                                color = Color.White,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = error!!,
                                color = errorColor,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                
                needsKeyFile -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .padding(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = cardBackgroundColor
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = primaryTextColor,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (language == "fr") 
                                        "Fichier de clé requis"
                                    else 
                                        "Key file required",
                                    color = primaryTextColor,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = if (language == "fr") 
                                        "Pour accéder à vos données hors ligne, veuillez sélectionner votre fichier de clé."
                                    else 
                                        "To access your offline data, please select your key file.",
                                    color = secondaryTextColor,
                                    fontSize = 14.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                
                                Button(
                                    onClick = { filePicker.launch("*/*") },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = primaryTextColor
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = fileName ?: if (language == "fr") "Sélectionner le fichier de clé" else "Select key file",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                
                                if (selectedFile != null) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = { loadOfflineDataWithKey(selectedFile!!) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = accentColor2
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                    ) {
                                        Text(
                                            text = if (language == "fr") "Charger les données" else "Load data",
                                            color = primaryTextColor,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                filteredPasswords.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = secondaryTextColor,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (language == "fr") 
                                    "Aucun mot de passe trouvé"
                                else 
                                    "No passwords found",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 16.sp
                            )
                        }
                    }
                }
                
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = filteredPasswords,
                            key = { (_, uuid) -> uuid.toString() }
                        ) { (password, uuid) ->
                            ReadOnlyPasswordItem(
                                password = password,
                                uuid = uuid,
                                language = language
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReadOnlyPasswordItem(
    password: Password,
    uuid: Uuid,
    language: String
) {
    val context = LocalContext.current
    var showContextMenu by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }

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
                                    while (isActive) {
                                        try {
                                            val currentTimeMillis = System.currentTimeMillis()
                                            val elapsedSeconds = (currentTimeMillis / 1000) % periodSeconds
                                            val remaining = periodSeconds - elapsedSeconds.toInt()
                                            
                                            if (remaining <= 0) {
                                                val newCode = OtpUtils.generate(tt)
                                                if (isActive) {
                                                    otpCode = newCode
                                                    remainingSeconds = periodSeconds
                                                }
                                            } else {
                                                val code = OtpUtils.generate(tt)
                                                if (isActive) {
                                                    otpCode = code
                                                    remainingSeconds = remaining
                                                }
                                            }
                                            
                                            delay(1000)
                                        } catch (e: CancellationException) {
                                            break
                                        } catch (e: Exception) {
                                            Log.e("OTP", "Error updating OTP code", e)
                                            break
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

                        // Badge pour mode lecture seule
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
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = if (language == "fr") "Lecture seule" else "Read-only",
                                    tint = backgroundColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (language == "fr") "Lecture seule" else "Read-only",
                                    fontSize = 12.sp,
                                    color = backgroundColor,
                                    fontWeight = FontWeight.Medium
                                )
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
        }
     }
 }