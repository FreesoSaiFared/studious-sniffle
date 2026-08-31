package science.transductive.nudge.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

class HealthContextAdapter(private val client: HealthConnectClient) {
    enum class Kind { STEPS, HEART_RATE, SLEEP }

    companion object {
        @JvmStatic
        fun readPermission(kind: Kind): String = when (kind) {
            Kind.STEPS -> HealthPermission.getReadPermission(StepsRecord::class)
            Kind.HEART_RATE -> HealthPermission.getReadPermission(HeartRateRecord::class)
            Kind.SLEEP -> HealthPermission.getReadPermission(SleepSessionRecord::class)
        }

        @JvmStatic
        fun requiredPermissions(kinds: Set<Kind>): Set<String> = kinds.map(::readPermission).toSet()
    }

    suspend fun granted(kinds: Set<Kind>): Set<Kind> {
        val permissions = client.permissionController.getGrantedPermissions()
        return kinds.filterTo(linkedSetOf()) { readPermission(it) in permissions }
    }

    suspend fun readSteps(start: Instant, end: Instant): List<StepsRecord> {
        requireGranted(Kind.STEPS)
        return client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end))
        ).records
    }

    suspend fun readHeartRate(start: Instant, end: Instant): List<HeartRateRecord> {
        requireGranted(Kind.HEART_RATE)
        return client.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, end))
        ).records
    }

    suspend fun readSleep(start: Instant, end: Instant): List<SleepSessionRecord> {
        requireGranted(Kind.SLEEP)
        return client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start, end))
        ).records
    }

    private suspend fun requireGranted(kind: Kind) {
        val required = readPermission(kind)
        val granted = client.permissionController.getGrantedPermissions()
        if (required !in granted) throw SecurityException("Health Connect permission not granted: $kind")
    }
}
