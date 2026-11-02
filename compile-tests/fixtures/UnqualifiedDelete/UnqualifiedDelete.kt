package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.DoubleQuotedQuestionMarkDialect
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.deleteFrom

private object Accounts : Table("accounts")

val unqualifiedDelete = SqlRenderer(DoubleQuotedQuestionMarkDialect).render(deleteFrom(Accounts))
