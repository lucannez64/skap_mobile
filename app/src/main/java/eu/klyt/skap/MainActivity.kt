package eu.klyt.skap

import android.app.Activity
import eu.klyt.skap.lib.createAccount
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.ComponentName
import android.os.Build
import android.view.autofill.AutofillManager
import eu.klyt.skap.autofill.SkapAutofillService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.klyt.skap.ui.theme.SkapTheme
import java.io.File
import java.util.*
import androidx.lifecycle.lifecycleScope
import eu.klyt.skap.lib.Decoded
import eu.klyt.skap.lib.tostring
import eu.klyt.skap.lib.auth
import eu.klyt.skap.lib.getAll
import kotlinx.coroutines.launch
import java.io.IOException
import android.content.SharedPreferences
import android.provider.OpenableColumns
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import com.google.gson.Gson
import eu.klyt.skap.lib.BincodeEncoder
import eu.klyt.skap.lib.ClientEx
import eu.klyt.skap.lib.OfflineStorageManager
import eu.klyt.skap.lib.rememberNetworkConnectivityState
import androidx.compose.material.icons.filled.CloudOff
import kotlinx.coroutines.CoroutineScope

var encodedFile: ByteArray? = null
const val REQUEST_SAVE_FILE = 42

// Constante pour les SharedPreferences
const val PREFS_NAME = "SkapPrefs"
const val PREF_LANGUAGE = "language"

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SkapTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1D1B21)
                ) {
                    LoginRegisterScreen(
                        onLoginSuccess = { clientEx, token ->
                            Log.d("MainActivity", "onLoginSuccess: starting post-login flow")
                            val selectedService = android.provider.Settings.Secure.getString(this.contentResolver, "autofill_service")
                            Log.d("MainActivity", "Selected autofill service=${selectedService ?: "null"}")

                            val shouldPrompt = shouldPromptToEnableOurAutofill(this)
                            Log.d(
                                "MainActivity",
                                "Autofill status: shouldPrompt=$shouldPrompt, enabled=${isAutofillServiceEnabled(this)}, oursSelected=${isOurAutofillServiceSelected(this)}"
                            )
                            if (shouldPrompt) {
                                Log.d("MainActivity", "Prompting user to enable/select our Autofill service")
                                requestAutofillServicePermission(this)
                                return@LoginRegisterScreen
                            }

                            // If our Autofill service is selected, sync the local Autofill DB
                            if (isOurAutofillServiceSelected(this)) {
                                Log.d("MainActivity", "Our Autofill service selected; starting DB sync")
                                lifecycleScope.launch {
                                    try {
                                        Log.d("MainActivity", "Invoking AutofillLoginSync.run")
                                        val syncResult = eu.klyt.skap.autofill.AutofillLoginSync.run(this@MainActivity, clientEx, token)
                                        syncResult.fold(
                                            onSuccess = { count ->
                                                Log.d("MainActivity", "Autofill DB sync completed with $count credentials")
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Autofill database synced ($count)",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            onFailure = { e ->
                                                Log.e("MainActivity", "Autofill DB sync failed: ${e.message}", e)
                                            }
                                        )
                                    } catch (t: Throwable) {
                                        Log.e("MainActivity", "Autofill DB sync error", t)
                                    }
                                }
                            } else {
                                Log.d("MainActivity", "Our Autofill service NOT selected; skipping DB sync")
                            }

                            // Lancer VaultActivity avec le ClientEx et le token
                            val intent = Intent(this, VaultActivity::class.java).apply {
                                putExtra("client_ex_bytes", encoderClientEx(clientEx))
                                putExtra("token", token)
                            }
                            Log.d("MainActivity", "Starting VaultActivity")
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }

    // Fonction pour encoder le ClientEx en ByteArray
    private fun encoderClientEx(clientEx: ClientEx): ByteArray {
        val encoder = BincodeEncoder()
        return encoder.encodeClientEx(clientEx)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SAVE_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(encodedFile)
                        outputStream.flush()
                    }
                    Toast.makeText(this, "Fichier sauvegardé avec succès!", Toast.LENGTH_SHORT).show()
                } catch (e: IOException) {
                    Log.e("FileSave", "Error saving file", e)
                    Toast.makeText(this, "Erreur lors de la sauvegarde du fichier: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// Définition des couleurs principales
val backgroundColor = Color(0xFF1D1B21)
val cardBackgroundColor = Color(0xFFCED7E1)
val primaryTextColor = Color(0xFF1D1B21)
val secondaryTextColor = Color(0xFF474B4F)
val accentColor1 = Color(0xFFF2C3C2)
val accentColor2 = Color(0xFFA7F3AE)
val errorColor = Color(0xFFE53E3E)

// Fonction pour sauvegarder la langue dans les SharedPreferences
fun saveLanguagePreference(context: Context, language: String) {
    val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putString(PREF_LANGUAGE, language)
    }
}

// Fonction pour récupérer la langue depuis les SharedPreferences
fun getLanguagePreference(context: Context): String {
    val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sharedPreferences.getString(PREF_LANGUAGE, 
        if (Locale.getDefault().language.startsWith("fr")) "fr" else "en"
    ) ?: if (Locale.getDefault().language.startsWith("fr")) "fr" else "en"
}

// Définition des traductions
data class Translations(
    val login: String,
    val createAccount: String,
    val createAccountDesc: String,
    val loginDesc: String,
    val email: String,
    val keyFile: String,
    val loading: String,
    val connect: String,
    val loginSuccess: String,
    val loginError: String,
    val creating: String,
    val create: String,
    val registerSuccess: String,
    val registerError: String,
    val alreadyAccount: String,
    val noAccount: String,
    val error: String
)

fun getTranslations(language: String): Translations {
    return if (language == "fr") {
        Translations(
            login = "Connexion",
            createAccount = "Créer un compte",
            createAccountDesc = "Créez un compte pour commencer à utiliser l'application.",
            loginDesc = "Entrez votre email et téléchargez votre fichier de clé pour vous connecter.",
            email = "Email",
            keyFile = "Fichier de clé",
            loading = "Chargement...",
            connect = "Se connecter",
            loginSuccess = "Connexion réussie!",
            loginError = "Erreur de connexion. Veuillez vérifier vos informations.",
            creating = "Création en cours...",
            create = "Créer un compte",
            registerSuccess = "Compte créé avec succès! Veuillez télécharger et conserver votre fichier de clé en lieu sûr.",
            registerError = "Erreur: ",
            alreadyAccount = "Déjà un compte? Se connecter",
            noAccount = "Pas encore de compte? S'inscrire",
            error = "Une erreur est survenue"
        )
    } else {
        Translations(
            login = "Login",
            createAccount = "Create Account",
            createAccountDesc = "Create an account to start using the application.",
            loginDesc = "Enter your email and upload your key file to log in.",
            email = "Email",
            keyFile = "Key File",
            loading = "Loading...",
            connect = "Log in",
            loginSuccess = "Login successful!",
            loginError = "Login error. Please check your information.",
            creating = "Creating account...",
            create = "Create Account",
            registerSuccess = "Account created successfully! Please download and keep your key file in a safe place.",
            registerError = "Error: ",
            alreadyAccount = "Already have an account? Log in",
            noAccount = "Don't have an account? Sign up",
            error = "An error occurred"
        )
    }
}

@Composable
fun LoginRegisterScreen(onLoginSuccess: (ClientEx, String) -> Unit) {
    // État pour la langue
    val context = LocalContext.current
    var language by remember { mutableStateOf(getLanguagePreference(context)) }
    val translations = getTranslations(language)
    
    // État pour la connectivité réseau
    val isConnected = rememberNetworkConnectivityState()
    val offlineStorageManager = remember { OfflineStorageManager.getInstance(context) }
    val hasOfflineData = remember { offlineStorageManager.hasOfflineData() }
    val isOfflineModeEnabled = remember { offlineStorageManager.isOfflineModeEnabled() }
    
    // État pour le formulaire
    var showRegisterForm by remember { mutableStateOf(false) }
    
    // État pour le formulaire de connexion
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    
    // État pour le formulaire d'inscription
    var registerEmail by remember { mutableStateOf("") }
    var registerEmailError by remember { mutableStateOf<String?>(null) }
    
    // État pour le chargement et les statuts
    var isLoading by remember { mutableStateOf(false) }
    var submitStatus by remember { mutableStateOf<String?>(null) }
    var registerStatus by remember { mutableStateOf<String?>(null) }
    var registerMessage by remember { mutableStateOf("") }
    
    val coroutineScope = rememberCoroutineScope()
    
    // Sélecteur de fichier
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
        Log.i(null,"$selectedFile")
        fileError = null
    }
    
    // Obtenir le lifecycleScope pour lancer des coroutines
    val lifecycleOwner = (context as? ComponentActivity)
    val activity = context as? ComponentActivity
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        // Sélecteur de langue - Remplacé par un bouton de profil
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 80.dp, end = 16.dp)
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
            }
        }
        
        // Carte principale
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp)),
            colors = CardDefaults.cardColors(
                containerColor = cardBackgroundColor
            )
        ) {
            Column {
                // En-tête de la carte
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .drawBehind {
                            val borderSize = 1.dp.toPx()
                            val y = size.height - borderSize/2
                            drawLine(
                                color = secondaryTextColor,
                                start = Offset(-60f, y),
                                end = Offset(size.width+60, y),
                                strokeWidth = borderSize
                            )
                        }
                ) {
                    Text(
                        text = if (showRegisterForm) translations.createAccount else translations.login,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryTextColor,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = if (showRegisterForm) translations.createAccountDesc else translations.loginDesc,
                        fontSize = 14.sp,
                        color = secondaryTextColor
                    )
                }
                
                // Contenu de la carte (formulaire)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (!showRegisterForm) {
                        // Formulaire de connexion
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Champ Fichier
                            Column {
                                Text(
                                    text = translations.keyFile,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryTextColor,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Button(
                                    onClick = { filePicker.launch("*/*") },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = primaryTextColor
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Text(
                                        text = fileName ?: translations.keyFile,
                                        color = accentColor1,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                                if (fileError != null) {
                                    Text(
                                        text = fileError!!,
                                        color = errorColor,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                            
                            // Bouton de connexion
                            Button(
                                onClick = {
                                    // Validation du formulaire
                                    var isValid = true
                                    
                                    if (selectedFile == null) {
                                        fileError = if (language == "fr") 
                                            "Veuillez sélectionner un fichier de clé." 
                                        else 
                                            "Please select a key file."
                                        isValid = false
                                    }
                                    
                                    if (isValid) {
                                        // get the file bytes
                                        if (selectedFile == null) {
                                            fileError = if (language == "fr")
                                                "Veuillez sélectionner un fichier de clé."
                                            else
                                                "Please select a key file."
                                            isValid = false
                                        } else {
                                            try {
                                                // Utiliser ContentResolver pour lire l'URI du fichier
                                                val fileBytes = context.contentResolver.openInputStream(selectedFile!!)?.use { 
                                                    it.readBytes() 
                                                }
                                                
                                                if (fileBytes == null) {
                                                    fileError = if (language == "fr")
                                                        "Impossible de lire le fichier de clé."
                                                    else
                                                        "Unable to read key file."
                                                    isValid = false
                                                } else {
                                                    val decodedFile = Decoded.decodeClientEx(fileBytes)
                                                    if (decodedFile == null) {
                                                        fileError = if (language == "fr")
                                                            "Le fichier de clé est invalide."
                                                        else
                                                            "The key file is invalid."
                                                        isValid = false
                                                    } else {
                                                        isLoading = true
                                                        lifecycleOwner?.lifecycleScope?.launch {
                                                            try {
                                                                val id = decodedFile.id.id ?: throw Exception("Invalid key file")
                                                                val authResult = auth(id, decodedFile.c)
                                                                authResult.fold(
                                                                    onSuccess = { token ->
                                                                        Log.i(null, "${decodedFile.c.secret?.tostring()}")
                                                                        submitStatus = "success"
                                                                        isLoading = false
                                                                        
                                                                        // Naviguer vers l'écran vault
                                                                        onLoginSuccess(decodedFile, token)
                                                                    },
                                                                    onFailure = { e ->
                                                                        fileError = if (language == "fr")
                                                                            "Erreur d'authentification: ${e.message}"
                                                                        else
                                                                            "Authentication error: ${e.message}"
                                                                        isValid = false
                                                                        isLoading = false
                                                                    }
                                                                )
                                                                
                                                            } catch (e: Exception) {
                                                                fileError = if (language == "fr")
                                                                    "Erreur inattendue: ${e.message}"
                                                                else
                                                                    "Unexpected error: ${e.message}"
                                                                isValid = false
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                fileError = if (language == "fr")
                                                    "Erreur lors de la lecture du fichier: ${e.message}"
                                                else
                                                    "Error reading file: ${e.message}"
                                                isValid = false
                                                Log.e("FileRead", "Error reading file", e)
                                            }
                                        }
                                    }
                                },
                                enabled = !isLoading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor1,
                                    disabledContainerColor = accentColor1.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .shadow(4.dp, RoundedCornerShape(6.dp))
                            ) {
                                Text(
                                    text = if (isLoading) translations.loading else translations.connect,
                                    color = primaryTextColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            
                            // Bouton hors ligne (affiché si des données hors ligne sont disponibles)
                            if (hasOfflineData && isOfflineModeEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Button(
                                    onClick = {
                                        // Naviguer vers l'écran hors ligne
                                        val intent = Intent(context, OfflineVaultActivity::class.java)
                                        context.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = primaryTextColor
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .shadow(4.dp, RoundedCornerShape(6.dp))
                                ) {
                                    Icon(
                                        imageVector = if (isConnected) Icons.Default.Storage else Icons.Default.CloudOff,
                                        contentDescription = null,
                                        tint = accentColor1,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (language == "fr") "Accéder aux mots de passe sauvegardés" else "Access Saved Passwords",
                                        color = accentColor1,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            
                            // Messages de statut
                            if (submitStatus == "success") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .background(accentColor2, RoundedCornerShape(6.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = translations.loginSuccess,
                                        color = primaryTextColor,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else if (submitStatus == "error") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .background(errorColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = translations.loginError,
                                        color = primaryTextColor,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    } else {
                        // Formulaire d'inscription
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Champ Email
                            Column {
                                Text(
                                    text = translations.email,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryTextColor,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                OutlinedTextField(
                                    value = registerEmail,
                                    onValueChange = { 
                                        registerEmail = it
                                        registerEmailError = null
                                    },
                                    placeholder = { Text("example@email.com") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            width = 1.dp,
                                            color = secondaryTextColor,
                                            shape = RoundedCornerShape(6.dp)
                                        ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = accentColor2,
                                        unfocusedBorderColor = secondaryTextColor,
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = Color.White
                                    ),
                                    textStyle = TextStyle(
                                        color = primaryTextColor,
                                        fontSize = 14.sp
                                    ),
                                    singleLine = true
                                )
                                if (registerEmailError != null) {
                                    Text(
                                        text = registerEmailError!!,
                                        color = errorColor,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                            
                            // Bouton d'inscription
                            Button(
                                onClick = {
                                    // Validation du formulaire
                                    var isValid = true
                                    
                                    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(registerEmail).matches()) {
                                        registerEmailError = if (language == "fr") 
                                            "Veuillez entrer une adresse email valide." 
                                        else 
                                            "Please enter a valid email address."
                                        isValid = false
                                    }
                                    
                                    if (isValid) {
                                        isLoading = true
                                        // Utiliser une coroutine pour appeler createAccount
                                        lifecycleOwner?.lifecycleScope?.launch {
                                            try {
                                                val r = createAccount(registerEmail)
                                                if (r.isFailure) {
                                                    println(r)
                                                    isLoading = false
                                                    registerStatus = "error"
                                                    registerMessage = r.exceptionOrNull()?.message ?: translations.registerError
                                                } else {
                                                    val d = r.getOrNull()
                                                    if (d != null) {
                                                        // SAVE FILE
                                                        encodedFile = d.encodedFile
                                                        // encodedFile is a ByteArray
                                                        // ask to save file with the default file manager
                                                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                                            addCategory(Intent.CATEGORY_OPENABLE)
                                                            type = "application/octet-stream" // Replace with appropriate MIME type
                                                            putExtra(Intent.EXTRA_TITLE, "client.key")
                                                        }
                                                        activity?.startActivityForResult(intent, REQUEST_SAVE_FILE)
                                                        registerStatus = "success"
                                                        registerMessage = translations.registerSuccess
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                registerStatus = "error"
                                                isLoading = false
                                                registerMessage = e.message ?: translations.error
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    }
                                },
                                enabled = !isLoading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor2,
                                    disabledContainerColor = accentColor2.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .shadow(4.dp, RoundedCornerShape(6.dp))
                            ) {
                                Text(
                                    text = if (isLoading) translations.creating else translations.create,
                                    color = primaryTextColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            
                            // Messages de statut
                            if (registerStatus == "success") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .background(accentColor2, RoundedCornerShape(6.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = registerMessage,
                                        color = primaryTextColor,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else if (registerStatus == "error") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .background(errorColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = "${translations.registerError} $registerMessage",
                                        color = primaryTextColor,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                    
                    // Lien pour basculer entre les formulaires
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (showRegisterForm) translations.alreadyAccount else translations.noAccount,
                            color = primaryTextColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clickable {
                                showRegisterForm = !showRegisterForm
                                // Réinitialisation des états
                                fileError = null
                                registerEmailError = null
                                submitStatus = null
                                registerStatus = null
                                registerMessage = ""
                            }
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginRegisterScreenPreview() {
    SkapTheme {
        LoginRegisterScreen(onLoginSuccess = { _, _ -> })
    }
}

fun requestAutofillServicePermission(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

fun isOurAutofillServiceSelected(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val selected = Settings.Secure.getString(context.contentResolver, "autofill_service") ?: return false
    val ours = ComponentName(context, SkapAutofillService::class.java).flattenToString()
    return selected == ours
}

fun shouldPromptToEnableOurAutofill(context: Context): Boolean {
    val afm = context.getSystemService(AutofillManager::class.java)
    val supported = afm?.isAutofillSupported == true
    val enabled = afm?.isEnabled == true
    val oursSelected = isOurAutofillServiceSelected(context)
    return supported && (!enabled || !oursSelected)
}

fun isAutofillServiceEnabled(context: Context): Boolean {
    val afm = context.getSystemService(AutofillManager::class.java)
    return afm?.hasEnabledAutofillServices() == true
}