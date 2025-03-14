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

class VaultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Récupérer le ClientEx et le token depuis l'intent
        val clientExBytes = intent.getByteArrayExtra("client_ex_bytes")
        val token = intent.getStringExtra("token")
        
        if (clientExBytes == null || token == null) {
            Toast.makeText(this, "Erreur: données de connexion manquantes", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Décoder le ClientEx
        val clientEx = Decoded.decodeClientEx(clientExBytes)
        
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
                        clientEx = clientEx,
                        token = token,
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
    var language by remember { mutableStateOf(
        if (Locale.getDefault().language.startsWith("fr")) "fr" else "en"
    ) }
    val translations = getTranslations(language)
    
    // État pour les mots de passe
    var passwords by remember { mutableStateOf<List<Pair<Password, Uuid>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // État pour la recherche
    var searchQuery by remember { mutableStateOf("") }
    
    // État pour l'ajout/édition
    var showAddForm by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var newRecord by remember { mutableStateOf(Password("", null, "", null, null, null)) }
    
    val context = LocalContext.current
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
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp, top=80.dp, end=16.dp)
    ) {
        Text(
            text = if (language == "fr") "Coffre-fort" else "Vault",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
        )
        // En-tête avec boutons de déconnexion et changement de langue
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {


            Button(
                onClick = { language = if (language == "fr") "en" else "fr" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = secondaryTextColor,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(38.dp)
            ) {
                Text(
                    text = if (language == "fr") "Français" else "English",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(
                    containerColor = errorColor,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(38.dp)
            ) {
                Text(
                    text = if (language == "fr") "Déconnexion" else "Logout",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        
        // Barre de recherche
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(if (language == "fr") "Rechercher..." else "Search...") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentColor2,
                unfocusedBorderColor = secondaryTextColor,
                focusedContainerColor = Color(0xFF2A2730),
                unfocusedContainerColor = Color(0xFF2A2730)
            ),
            textStyle = TextStyle(color = Color.White),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
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
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = primaryTextColor,
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
                    items(filteredPasswords.size) { index ->
                        val (password, uuid) = filteredPasswords[index]
                        PasswordItem(
                            password = password,
                            uuid = uuid,
                            language = language,
                            onEdit = { /* Implémentation de l'édition */ },
                            onDelete = { /* Implémentation de la suppression */ }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PasswordItem(
    password: Password,
    uuid: Uuid,
    language: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // États pour l'OTP
    var otpCode by remember { mutableStateOf<String?>(null) }
    var remainingSeconds by remember { mutableStateOf(0) }
    var periodSeconds by remember { mutableStateOf(30) } // Période par défaut
    
    // Analyser et générer l'OTP si disponible
    LaunchedEffect(password.otp) {
        if (password.otp != null) {
            try {
                val params = OTPParameters.parseUrl(password.otp)
                periodSeconds = params.period.value
                
                // Calculer le temps initial
                val initialTimeMillis = System.currentTimeMillis()
                val initialElapsedSeconds = (initialTimeMillis / 1000) % periodSeconds
                remainingSeconds = periodSeconds - initialElapsedSeconds.toInt()
                
                // Générer le code initial
                otpCode = TOTPGenerator.now(params)
                
                // Lancer une coroutine pour mettre à jour uniquement quand nécessaire
                while (true) {
                    // Calculer le délai jusqu'à la prochaine seconde
                    val currentTimeMillis = System.currentTimeMillis()
                    val delayToNextSecond = 1000 - (currentTimeMillis % 1000)
                    
                    // Attendre jusqu'à la prochaine seconde exacte
                    delay(delayToNextSecond)
                    
                    // Calculer le temps restant
                    val newTimeMillis = System.currentTimeMillis()
                    val elapsedSeconds = (newTimeMillis / 1000) % periodSeconds
                    val newRemainingSeconds = periodSeconds - elapsedSeconds.toInt()
                    
                    // Mettre à jour le temps restant
                    remainingSeconds = newRemainingSeconds
                    
                    // Régénérer le code uniquement lorsque nécessaire (au début d'un nouveau cycle)
                    if (newRemainingSeconds == periodSeconds - 1 || newRemainingSeconds == periodSeconds) {
                        otpCode = TOTPGenerator.now(params)
                    }
                }
            } catch (e: Exception) {
                Log.e("OTP", "Erreur lors de l'analyse de l'URI OTP: ${e.message}")
                otpCode = null
            }
        }
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(
            containerColor = cardBackgroundColor
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            // Service/App avec icône
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                // Favicon pour les URLs ou icône par défaut pour les app_id
                if (password.url != null) {
                    // Extraire le domaine de l'URL pour l'API Google Favicon
                    val domain = try {
                        Uri.parse(password.url).host ?: password.url
                    } catch (e: Exception) {
                        password.url
                    }
                    
                    // Utiliser l'API Google Favicon avec chargement en arrière-plan
                    Box(
                        modifier = Modifier.size(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data("https://www.google.com/s2/favicons?domain=$domain&sz=64")
                                .crossfade(true)
                                .coroutineContext(Dispatchers.IO)
                                .memoryCachePolicy(coil3.request.CachePolicy.ENABLED) // Activer le cache mémoire
                                .diskCachePolicy(coil3.request.CachePolicy.ENABLED) // Activer le cache disque
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    // Icône par défaut pour les app_id
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = accentColor1,
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = password.url ?: password.app_id ?: (if (language == "fr") "Service non spécifié" else "Unspecified service"),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Username et Password dans une seule ligne pour gagner de l'espace
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Colonne pour les labels et valeurs
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Username
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (language == "fr") "Utilisateur:" else "User:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = secondaryTextColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (password.username.length > 20) password.username.slice(0..20) + ".." else password.username,
                            fontSize = 12.sp,
                            color = primaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("username", password.username)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(
                                    context,
                                    if (language == "fr") "Nom d'utilisateur copié" else "Username copied",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .padding(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = if (language == "fr") "Copier" else "Copy",
                                tint = secondaryTextColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    
                    // Password
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (language == "fr") "Mot de passe:" else "Pass:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = secondaryTextColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showPassword) {
                                if (password.password.length > 18) password.password.slice(0..17) + "..." else password.password
                            } else "••••••••",
                            fontSize = 12.sp,
                            color = primaryTextColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Row {
                            IconButton(
                                onClick = { showPassword = !showPassword },
                                modifier = Modifier
                                    .size(32.dp)
                                    .padding(2.dp)
                            ) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) (if (language == "fr") "Cacher" else "Hide") else (if (language == "fr") "Afficher" else "Show"),
                                    tint = secondaryTextColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("password", password.password)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(
                                        context,
                                        if (language == "fr") "Mot de passe copié" else "Password copied",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .padding(2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = if (language == "fr") "Copier" else "Copy",
                                    tint = secondaryTextColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                
                // Boutons d'action
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Bouton Modifier avec coins moins prononcés
                    Button(
                        onClick = onEdit,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = backgroundColor.copy(alpha = 0.9f),
                            contentColor = accentColor2
                        ),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    
                    // Bouton Supprimer
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = errorColor
                        ),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            
            // OTP (si présent et code généré)
            if (password.otp != null && otpCode != null) {
                // Utiliser remember pour éviter les recalculs inutiles
                val percentage by remember(remainingSeconds, periodSeconds) {
                    mutableStateOf((remainingSeconds.toFloat() / periodSeconds.toFloat()) * 100)
                }
                
                // Couleur basée sur le temps restant - calculée une seule fois par rendu
                val circleColor by remember(remainingSeconds) {
                    mutableStateOf(
                        when {
                            remainingSeconds <= 5 -> errorColor
                            remainingSeconds <= 10 -> Color(0xFFFFA500) // Orange
                            else -> accentColor2
                        }
                    )
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    // Étiquette et code OTP
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (language == "fr") "Code 2FA:" else "2FA Code:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = secondaryTextColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = otpCode!!,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor2
                        )
                        Spacer(modifier = Modifier.width(8.dp))
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
                            modifier = Modifier
                                .size(32.dp)
                                .padding(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = if (language == "fr") "Copier" else "Copy",
                                tint = secondaryTextColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    
                    // Indicateur circulaire du temps restant
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(32.dp)
                    ) {
                        // Cercle de fond
                        CircularProgressIndicator(
                            progress = 1f,
                            modifier = Modifier.size(32.dp),
                            color = Color.Gray.copy(alpha = 0.2f),
                            strokeWidth = 3.dp,
                            strokeCap = StrokeCap.Round
                        )
                        
                        // Cercle de progression
                        CircularProgressIndicator(
                            progress = percentage / 100,
                            modifier = Modifier.size(32.dp),
                            color = circleColor,
                            strokeWidth = 3.dp,
                            strokeCap = StrokeCap.Round
                        )
                        
                        // Texte du temps restant
                        Text(
                            text = remainingSeconds.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryTextColor
                        )
                    }
                }
            } else if (password.otp != null) {
                // Afficher un message si l'OTP est présent mais pas encore généré
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = if (language == "fr") "2FA:" else "2FA:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = secondaryTextColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (language == "fr") "Disponible" else "Available",
                        fontSize = 12.sp,
                        color = accentColor2
                    )
                }
            }
        }
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