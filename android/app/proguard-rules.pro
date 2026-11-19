# Les dades de Firestore es llegeixen a mà (sense toObject), així que no cal conservar els models.
# Regles recomanades per Credential Manager:
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
