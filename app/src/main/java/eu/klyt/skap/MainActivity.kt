package eu.klyt.skap

import android.app.Activity
import eu.klyt.skap.lib.createAccount
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
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
import eu.klyt.skap.lib.auth
import kotlinx.coroutines.launch
import java.io.IOException

var encodedFile: ByteArray? = null
const val REQUEST_SAVE_FILE = 42

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SkapTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1D1B21)
                ) {
                    LoginRegisterScreen()
                }
            }
        }
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
val errorColor = Color(0xFFB00E0B)


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
fun LoginRegisterScreen() {
    // État pour la langue
    var language by remember { mutableStateOf(
        if (Locale.getDefault().language.startsWith("fr")) "fr" else "en"
    ) }
    val translations = getTranslations(language)
    
    // État pour le formulaire
    var showRegisterForm by remember { mutableStateOf(false) }
    
    // État pour le formulaire de connexion
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    
    // État pour le formulaire d'inscription
    var registerEmail by remember { mutableStateOf("") }
    var registerEmailError by remember { mutableStateOf<String?>(null) }
    
    // État pour le chargement et les statuts
    var isLoading by remember { mutableStateOf(false) }
    var submitStatus by remember { mutableStateOf<String?>(null) }
    var registerStatus by remember { mutableStateOf<String?>(null) }
    var registerMessage by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    
    // Sélecteur de fichier
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        selectedFile = uri
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
        // Sélecteur de langue
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 80.dp, end = 16.dp)
        ) {
            Button(
                onClick = { language = if (language == "fr") "en" else "fr" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = secondaryTextColor,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = if (language == "fr") "Français" else "English",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
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
                                        text = selectedFile?.lastPathSegment ?: translations.keyFile,
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
                                                                    onSuccess = { success ->
                                                                        if (!success) {
                                                                            fileError = if (language == "fr")
                                                                                "Échec de l'authentification"
                                                                            else 
                                                                                "Authentication failed"
                                                                            isValid = false
                                                                        }
                                                                    },
                                                                    onFailure = { e ->
                                                                        fileError = if (language == "fr")
                                                                            "Erreur d'authentification: ${e.message}"
                                                                        else
                                                                            "Authentication error: ${e.message}"
                                                                        isValid = false
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
                                                        // Simulation d'une connexion réussie
                                                        submitStatus = "success"
                                                        isLoading = false
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

                                        // Dans une vraie application, vous appelleriez votre API d'authentification ici
                                        // et géreriez la réponse
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
        LoginRegisterScreen()
    }
}