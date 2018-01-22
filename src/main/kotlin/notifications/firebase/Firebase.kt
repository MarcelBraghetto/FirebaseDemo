/*
MIT License

Copyright (c) 2018 Marcel Braghetto

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package notifications.firebase

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import notifications.firebase.PublishError.*
import notifications.framework.Jackson
import notifications.framework.Platform
import notifications.framework.parse
import notifications.framework.toJson
import notifications.repository.Registration
import notifications.repository.RegistrationRepository
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.FileInputStream

object Firebase {
    // This demo uses the most recent method of connecting to Firebase via the 'FCM HTTP v1 API', which uses OAUTH2.
    // You will need to create and download a private key from the Firebase console and set it up before continuing:
    //
    // https://firebase.google.com/docs/cloud-messaging/auth-server
    //
    // Also, before calling the Firebase APIs for the first time you must enable the 'Firebase Cloud Messaging API' for
    // your project. Visit the following link and enable Firebase APIs for your project:
    //
    // https://console.developers.google.com/apis/library/fcm.googleapis.com/?project=<your_firebase_project_id>

    // The 'service-account.json' file in this repository is just a stub - you will need to replace it with your own
    // that you download from the Firebase console after creating your private key.
    private const val firebaseServiceAccountFile = "service-account.json"

    // This is your project id as found in the Firebase developer console.
    private const val firebaseProjectId = "<your_firebase_project_id>"

    // The Firebase messaging API requests go through this url.
    private const val firebaseUrl = "https://fcm.googleapis.com/v1/projects/$firebaseProjectId/messages:send"

    // The authentication integration needs us to define the 'scopes' which for Firebase messaging is the following:
    private val firebaseScopes = listOf("https://www.googleapis.com/auth/firebase.messaging")

    // We will be sending JSON content to Firebase.
    private val jsonMediaType = MediaType.parse("application/json; charset=utf-8")!!

    // This demo uses OkHttp to perform the POST requests, but you could use whatever HTTP client you prefer.
    private val httpClient by lazy { OkHttpClient() }

    /**
     * Registration of a mobile device simply requires storing the Firebase registration ID for later use.
     * In this example, we are recording what platform the user is registering on (iOS or Android) but actually
     * Firebase doesn't necessarily need to know the platform when sending publications out. It can be helpful
     * to keep the platform though, as iOS and Android notification payloads can have some differing key/values
     * and you may want to send notifications to specific platforms for some reason.
     */
    fun register(platform: Platform, userId: String, registrationId: String) {
        RegistrationRepository.storeRegistration(Registration(platform, userId, registrationId))
    }

    // In this demo we will use the 'service account' approach to obtain an OAUTH2 token for sending requests -
    // this is instead of the 'legacy' approach where a server api key is sent in the headers. The OAUTH2
    // approach offers better security and is the most recent authentication method at the time this demo was created.
    private val firebaseAccessToken: String
        get() {
            return GoogleCredential
                    .fromStream(FileInputStream(firebaseServiceAccountFile))
                    .createScoped(firebaseScopes).run {
                // We need to ask our token to be refreshed in case it has expired.
                refreshToken()
                // Then we can just return whatever token we have been issued to use for authentication.
                accessToken
            }
        }

    /**
     * Given some information about a desired notification, connect to Firebase and request that it publish the
     * notification to the platforms that are activated in the Firebase console.
     */
    fun publishNotification(userId: String, notificationTitle: String, notificationMessage: String) {
        // As we send the notification requests we will track any that had a failed response so they can be cleaned
        // up if necessary or retried later.
        val failedRequests = mutableListOf<Pair<Registration, PublishError>>()

        // Fetch the list of registration ids associated with the given user and for each one, request that Firebase
        // send a notification.
        RegistrationRepository.getRegistrations(userId).forEach { registration ->
            // Create a notification payload that meets the Firebase API requirements.
            val payload = createNotification(registration.registrationId, notificationTitle, notificationMessage)

            println("Starting Firebase notification request with payload: $payload")

            // Submit a HTTP POST request with the payload, Firebase will then forward the notification to
            // the appropriate platform service provider (GCM/Firebase for Android, APNS for iOS). Because we are using
            // the service account and OAUTH2 authentication approach, we need to obtain a current access token, which
            // will refresh itself as needed any time we invoke the 'firebaseAccessToken' property in this class.
            httpClient.newCall(
                    Request.Builder()
                            .url(firebaseUrl)
                            .addHeader("Authorization", "Bearer $firebaseAccessToken")
                            .addHeader("Content-Type", "application/json; UTF-8")
                            .post(RequestBody.create(jsonMediaType, payload))
                            .build()
            ).execute().run {
                // Attempt to read the response body and bail if it fails. Probably you would want to mark the
                // publish operation as a failure and try to execute it again as its likely the notifications
                // were not triggered.
                val responseText = body()?.string()

                if (responseText == null) {
                    println("Network connectivity error")
                    failedRequests.add(Pair(registration, NetworkError))
                    return@forEach
                }

                println("Status code: ${code()}, received response: $responseText")

                // Something caused the request to fail at the status code level, normally this is a server problem,
                // or something about the data in the request was rejected (e.g. an invalid registration id)
                if (!isSuccessful) {
                    // See if we can parse the response into a meaningful error
                    responseText.parse<PublishErrorResponse>()?.run {
                        if (isInvalidRegistrationTokenError()) {
                            failedRequests.add(Pair(registration, InvalidRegistrationIdError))
                        } else {
                            // Probably you would want to examine other error codes here.
                            failedRequests.add(Pair(registration, UnknownError))
                        }
                    } ?: failedRequests.add(Pair(registration, ResponseParsingError))
                }
            }
        }

        // After submitting all the notification requests, we can iterate any failures and decide what to do about them.
        for ((registration, error) in failedRequests) {
            println("Failed with error: $error to ${registration.platform}: ${registration.registrationId}")

            // This is a chance to do any lifecycle clean up etc.
            if (error == InvalidRegistrationIdError) {
                println("-> Registration token was invalid - removing from repository")
                RegistrationRepository.removeRegistration(registration)
            }
        }

        // Note: if there are failures, it is up to you to accommodate any retry behaviours.
    }
}

/**
 * Create a sample notification JSON payload to submit to Firebase.
 *
 * https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages
 * https://firebase.google.com/docs/cloud-messaging/http-server-ref
 * https://firebase.googleblog.com/2017/11/whats-new-with-fcm-customizing-messages.html
 */
private fun createNotification(registrationId: String, title: String, body: String): String {
    // A bit of demo custom data
    val customKey1 = "CustomKey1"
    val customValue1 = "CustomValue1"

    Jackson.mapper.run {
        // region Android specific - adjust as needed
        val androidData = createObjectNode().apply {
            put(customKey1, customValue1)
        }

        val android = createObjectNode().apply {
            put("priority", "HIGH")
            set("data", androidData)
        }
        // endregion

        // region iOS specific - adjust as needed
        val apnsPayloadAps = createObjectNode().apply {
            // https://developer.apple.com/library/content/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/CreatingtheNotificationPayload.html#//apple_ref/doc/uid/TP40008194-CH10-SW8
            put("content-available", 1)

            // https://developer.apple.com/library/content/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/ModifyingNotifications.html
            put("mutable-content", 1)
        }

        val apnsPayload = createObjectNode().apply {
            set("aps", apnsPayloadAps)
            put(customKey1, customValue1)
        }

        val apns = createObjectNode().apply {
            set("payload", apnsPayload)
        }
        // endregion

        // region Common 'notification' object
        val notification = createObjectNode().apply {
            put("title", title)
            put("body", body)
        }

        // General 'message' object containing all the configuration
        val message = createObjectNode().apply {
            put("token", registrationId)
            set("notification", notification)
            set("android", android)
            set("apns", apns)
        }
        // endregion

        return createObjectNode().apply {
            set("message", message)
        }.toJson()
    }
}
