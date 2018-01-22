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

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.node.ObjectNode

/*
If an error occurs while publishing to a registration id, a response that looks something similar to the
following will be returned:
{
  "error": {
    "code": 400,
    "message": "Request contains an invalid argument.",
    "status": "INVALID_ARGUMENT",
    "details": [
      {
        "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
        "errorCode": "INVALID_ARGUMENT"
      },
      {
        "@type": "type.googleapis.com/google.rpc.BadRequest",
        "fieldViolations": [
          {
            "field": "message.token",
            "description": "Invalid registration token"
          }
        ]
      }
    ]
  }
}
 */
class PublishErrorResponse(@JsonProperty("error") val error: Error) {
    companion object {
        const val FIELD = "field"
        const val FIELD_VIOLATIONS = "fieldViolations"
        const val MESSAGE_TOKEN_VIOLATION = "message.token"
    }

    class Error(
            @JsonProperty("code") val code: Int,
            @JsonProperty("message") val message: String,
            @JsonProperty("status") val status: String,
            @JsonProperty("details") val details: Array<ObjectNode>?
    )

    // We can check if there is a message token problem by searching for the message token field violation.
    fun isInvalidRegistrationTokenError(): Boolean {
        error.details?.let { details ->
            val fieldViolationsNode = details.firstOrNull { it.has(FIELD_VIOLATIONS) && it[FIELD_VIOLATIONS].isArray } ?: return false
            val fieldNode = fieldViolationsNode[FIELD_VIOLATIONS].firstOrNull { it.has(FIELD) && it[FIELD].isTextual } ?: return false
            return fieldNode[FIELD].textValue() == MESSAGE_TOKEN_VIOLATION
        }

        return false
    }
}
