// ============================================================================
// Обновление data_provider у проверок (DQImplementation), которые были «отняты»
// у published-версии при публикации DQLA (переход st: 50 -> 70).
// Проверки, оставшиеся без других published-родителей, получают пустой
// data_provider; у остальных representer берётся из published-версий других
// родителей.
// ============================================================================

if (oldVersion == null) {
    return
}
if (st != '50' || stnew != '70') {
    return
}

LOG.info("[BS DQLA] Start upd deleted impls")

def oldVersionNum = instance.version
if (oldVersionNum.startsWith("0.") || oldVersionNum == "1.0.0.0") {
    return
}

int parentId = instance.getBusinessId()

// ----------------------------------------------------------------------------
// 1. SQL: находим проверки, отнятые у текущей версии
// ----------------------------------------------------------------------------
def sql = """
    WITH
    -- ---- ОПУБЛИКОВАННАЯ ВЕРСИЯ ----
    pub_ind_ids AS (
        SELECT odt.id AS ind_id
        FROM reference_table r1
        JOIN object_data_table odt ON odt.id = r1.from_id
        WHERE r1.to_business_id = ${parentId}
        AND r1.from_type = 'DQIndicator'
        AND r1.to_type   = 'DQLA'
        AND odt.stage = 'published'
    ),
    pub_checks AS (
        SELECT DISTINCT r2.to_business_id AS chk_biz_id
        FROM pub_ind_ids pi
        JOIN reference_table r2
            ON  r2.from_id   = pi.ind_id
            AND r2.from_type = 'DQIndicator'
            AND r2.to_type   = 'DQImplementation'
        JOIN object_data_table odt
            ON  odt.business_id = r2.to_business_id
            AND odt.is_active   = true
    ),

    -- ---- ТЕКУЩАЯ ВЕРСИЯ ----
    cur_ind_ids AS (
        SELECT odt.id AS ind_id
        FROM reference_table r1
        JOIN object_data_table odt ON odt.id = r1.from_id
        WHERE r1.to_business_id = ${parentId}
        AND r1.from_type = 'DQIndicator'
        AND r1.to_type   = 'DQLA'
        AND odt.is_active = true
    ),
    cur_checks AS (
        SELECT DISTINCT r2.to_business_id AS chk_biz_id
        FROM cur_ind_ids ci
        JOIN reference_table r2
            ON  r2.from_id   = ci.ind_id
            AND r2.from_type = 'DQIndicator'
            AND r2.to_type   = 'DQImplementation'
        JOIN object_data_table odt
            ON  odt.business_id = r2.to_business_id
            AND odt.is_active   = true
    ),

    -- ---- DIFF ----
    removed AS (
        SELECT chk_biz_id FROM pub_checks
        EXCEPT
        SELECT chk_biz_id FROM cur_checks
    ),

    -- ---- владельцы отнятых проверок ----
    step4 AS (
        SELECT DISTINCT rm.chk_biz_id, r4.to_business_id AS owner_biz_id
        FROM removed rm
        JOIN reference_table r3
            ON  r3.to_business_id = rm.chk_biz_id
            AND r3.from_type = 'DQIndicator'
            AND r3.to_type   = 'DQImplementation'
        JOIN reference_table r4
            ON  r4.from_id   = r3.from_id
            AND r4.from_type = 'DQIndicator'
            AND r4.to_type   = 'DQLA'
    ),
    -- representer берём из published-версий ДРУГИХ родителей
    main_result AS (
        SELECT s4.chk_biz_id   AS t_id,
            s4.owner_biz_id AS res_id,
            odt.json_obj ->> 'representer' AS jsn
        FROM step4 s4
        JOIN object_data_table odt ON odt.business_id = s4.owner_biz_id
        WHERE odt.is_active = true
        AND odt.stage     = 'published'
        AND s4.owner_biz_id <> ${parentId}
    )
    SELECT DISTINCT t_id, res_id, jsn
    FROM (
        -- у проверки есть «другие» опубликованные родители
        SELECT * FROM main_result
        UNION ALL
        -- иначе — только id проверки, без владельца
        SELECT rm.chk_biz_id, NULL::bigint, NULL::text
        FROM removed rm
        WHERE NOT EXISTS (
            SELECT 1 FROM main_result m WHERE m.t_id = rm.chk_biz_id
        )
    ) x
    ORDER BY t_id, res_id NULLS LAST;
"""

def data = []
def connection, statement, rs

def tStartSql = new Date()
try {
    connection = jdbcTemplate.getDataSource().getConnection()
    statement = connection.createStatement()
    rs = statement.executeQuery(sql)
    while (rs.next()) {
        data << [
            t_id: rs.getLong("t_id"),
            jsn:  rs.getString("jsn")
        ]
    }
} finally {
    rs?.close()
    statement?.close()
    connection?.close()
}
LOG.info("[BS DQLA] sql time = ${new Date().time - tStartSql.time} ms, rows = ${data.size()}")

if (data.isEmpty()) {
    LOG.info("Проверок, отнятых от published-версии, нет — выходим")
    return
}

// ----------------------------------------------------------------------------
// 2. Токен для вызова процедуры обновления
// ----------------------------------------------------------------------------
def baseUrl = "https://dg-dev.isb"
def tokenParams = [
    "baseUrl":      baseUrl,
    "username":     "bgadmin",
    "password":     "BgAdmin!",
    "clientId":     "Business-glossarium-client",
    "clientSecret": "",
    "disableSsl":   true
]
def token = runAsService.runAsAdmin("GetTokenProcedure", instance, tokenParams)

// ----------------------------------------------------------------------------
// 3. Группировка по проверке и извлечение id/name из JSON representer
// ----------------------------------------------------------------------------
def changesByCheck = data.groupBy { it.t_id }.collectEntries { tId, rows ->
    def users = rows.collectMany { row -> extractUsers(row.jsn) }
                     .unique { [it.id, it.name] }
    def providers = users.collect { user -> buildProvider(user.id, user.name) }
    [(tId): ["data_provider": providers]]
}

// ----------------------------------------------------------------------------
// 4. Подготовка задач (чистые вычисления, без внешних вызовов)
// ----------------------------------------------------------------------------
def workItems = changesByCheck.collect { tId, changesMap ->
    def providers = (changesMap?.data_provider ?: []).unique { it.id }
    [tId: tId, changes: ["data_provider": providers]]
}
LOG.info("[BS DQLA] items to update = ${workItems.size()}")

// ----------------------------------------------------------------------------
// 5. Параллельное обновление (каждый tId независим)
// ----------------------------------------------------------------------------
def tStartLoop = new Date()
def workerCount = Math.max(1, Math.min(8, workItems.size())) // можно подстроить под сервер
def executor = java.util.concurrent.Executors.newFixedThreadPool(workerCount)
def failures = new java.util.concurrent.ConcurrentLinkedQueue()

try {
    def futures = workItems.collect { item ->
        executor.submit({
            try {
                def ch = instanceService.getInstance(item.tId)
                def updateParams = [
                    "baseUrl":       baseUrl,
                    "token":         token,
                    "businessId":    item.tId,
                    "objectType":    "DQImplementation",
                    "changesDct":    item.changes,
                    "version":       ch.version,
                    "migrationMode": (ch?.typedAttrByName(InstanceService.STATUS_FIELD) == '70')
                ]
                runAsService.runAsAdmin("UpdateProcedure", ch, updateParams)
            } catch (Throwable e) {
                LOG.warning("[BS DQLA] error for tId = ${item.tId}: ${e}")
                failures << item.tId
            }
        } as java.util.concurrent.Callable)
    }
    futures.each { it.get() }
} finally {
    executor.shutdown()
}

LOG.info("[BS DQLA] update loop time = ${new Date().time - tStartLoop.time} ms")
if (!failures.isEmpty()) {
    LOG.warning("[BS DQLA] failed tIds = ${failures.toList()}")
}
LOG.info("[BS DQLA] end upd deleted impls")


// ============================================================================
// Helpers
// ============================================================================

// Извлекает список [id, name] из JSON-строки representer.
// Если строка пустая/null (проверка без других родителей) — возвращает [].
def extractUsers(String jsonStr) {
    if (!jsonStr) {
        return []
    }
    def parsed = new groovy.json.JsonSlurper().parseText(jsonStr)
    def list = (parsed instanceof Collection) ? parsed : [parsed]
    return list.findAll { it?.id != null }.collect {
        [id: it.id as Long, name: it.name as String]
    }
}

def buildProvider(Long id, String name) {
    [
        "type": "User",
        "id": id,
        "name": name,
        "relation": [
            "type": "TECHOWNER",
            "description": ""
        ]
    ]
}
