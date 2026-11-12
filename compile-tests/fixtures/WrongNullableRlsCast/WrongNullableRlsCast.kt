package fixtures
import dev.quwac.kagiroi.postgres.currentSetting
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.cast
import kotlin.uuid.Uuid
val unsafe: SqlExpression<Uuid> = currentSetting("app.tenant_id", missingOk = true).cast<Uuid>()
