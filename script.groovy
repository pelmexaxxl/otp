log.info("[AS DQLA] start upd impls")
def TStartUpdImpls = new Date()
// if (OldVersion.attrByName('representer').toSet() == instance.attrByName('representer').toSet()) { return }
// def human =  rep_attr.toSet()

// Вызов процедуры getAuthToken
def baseUrl = "https://dg-dev.isb"
def username = "bgadmin"
def password = "BgAdmin!"
def clientId = "Business-glossarium-client"
// Параметры для процедуры
def params = [
    "baseUrl": baseUrl,
    "username": username,
    "password": password,
    "clientId": clientId,
    "clientSecret": "",
    "disableSsl": true
]
def token = runAsService.runAsAdmin("GetTokenProcedure", instance, params)

int PARENT_ID = instance.getBusinessId()
// log.info("PARENT_ID = ${PARENT_ID}")
def myRepList = []
def my_rep = instance.attrByName("representer")
// log.info("my_rep (on dqla) raw = ${my_rep}")
if (my_rep != null) {
    my_rep.each { refValue ->
        myRepList << [
            "type": "User",
            "id": refValue['id'],
            "name": refValue['name'],
            "relation": [
                "type": "TECHOWNER",
                "description": ""
            ]
        ]
    }
}
// log.info("[AS DQLA] myRepList (on dqla) = ${myRepList}")

def connection
def statement
def rs

def sql = """
    WITH 
    step2 AS (
        SELECT DISTINCT r2.to_business_id AS t_id
        FROM reference_table r1
        JOIN reference_table r2 ON r2.from_id = r1.from_id
            AND r2.from_type = 'DQIndicator'
            AND r2.to_type = 'DQImplementation'
        WHERE r1.from_type = 'DQIndicator' 
            AND r1.to_type = 'DQLA' 
            AND r1.to_business_id = ${PARENT_ID}
    ),
    step4 AS (
        SELECT DISTINCT s2.t_id, r4.to_business_id AS res_id
        FROM step2 s2
        JOIN reference_table r3 ON r3.to_business_id = s2.t_id
            AND r3.from_type = 'DQIndicator'
        JOIN reference_table r4 ON r4.from_id = r3.from_id
            AND r4.to_type = 'DQLA'
    ),
    -- обычные записи, прошедшие фильтры
    main_result AS (
        SELECT 
            s4.t_id,
            s4.res_id,
            odt.json_obj ->> 'representer' AS jsn
        FROM step4 s4
        JOIN object_data_table odt ON odt.business_id = s4.res_id
        WHERE odt.is_active = true 
            AND odt.stage = 'published'
    ),
    -- родитель: по одному t_id из step2, jsn без фильтров
    parent_result AS (
        SELECT
            s2.t_id,
            ${PARENT_ID} AS res_id,
            odt.json_obj ->> 'representer' AS jsn
        FROM step2 s2
        LEFT JOIN object_data_table odt 
            ON odt.business_id = ${PARENT_ID}
        -- фильтры is_active/stage НЕ применяем
    )
    SELECT distinct t_id, res_id, jsn
    FROM (
        SELECT t_id, res_id, jsn FROM main_result
        UNION ALL
        SELECT t_id, res_id, jsn FROM parent_result
        WHERE NOT EXISTS (
            SELECT 1 FROM main_result m
            WHERE m.res_id = ${PARENT_ID} AND m.t_id = parent_result.t_id
        )
    ) x;
"""

// dataRaw = statement.executeQuery(sql)
def data = []

def TStartSql = new Date()
try {
connection = jdbcTemplate.getDataSource().getConnection()
statement = connection.createStatement()
rs = statement.executeQuery(sql)
// log.info("rs = ${rs}")
// log.info("${sql}")
    while (rs.next()) {
        // log.info("rs.next = ${rs}")
        data << [
            t_id: rs.getLong("t_id"),
            res_id: rs.getLong("res_id"),
            jsn: rs.getString("jsn") // получаем как строку
        ]
    }
} finally {
    if (rs != null) { rs.close() }
    if (statement != null) { statement.close() }
    if (connection != null) { connection.close() }
}
log.info("[AS DQLA] sql time = ${TimeCategory.minus(new Date(), TStartSql)}")
// log.info("data (sql) = ${data}")

// Группировка и извлечение id/name из JSON строки
def result = data.groupBy { it.t_id }.collectEntries { tId, rows ->
    def users = []
    
    rows.each { row ->
        def jsonStr = row.jsn
        // Извлекаем id и name из JSON строки через регулярки
        def pattern = ~/"id":\s*(\d+),\s*"name":\s*"([^"]+)"/
        def matcher = pattern.matcher(jsonStr)
        
        while (matcher.find()) {
            users << [
                id: matcher.group(1) as Long,
                name: matcher.group(2)
            ]
        }
    }
    // log.info("users = ${users}")
    def uniqueUsers = users.unique { [it.id, it.name] }
    // log.info("uniqueUsers = ${uniqueUsers}")
    def changesMap = uniqueUsers.isEmpty() ? ["data_provider": []] : [
        "data_provider": uniqueUsers.collect { user ->
            [
                "type": "User",
                "id": user.id,
                "name": user.name,
                "relation": [
                    "type": "TECHOWNER",
                    "description": ""
                ]
            ]
        }
    ]
    // log.info("changesMap = ${changesMap}")
    [(tId): changesMap]
}.findAll { it.value != null }


// log.info("result = ${result}")

// 1) Подготовка задач: только быстрые вычисления в памяти (без внешних вызовов)
def workItems = []
result.each { tId, changesMap ->
    def existingProviders = changesMap?.data_provider ?: []
    def allProviders = existingProviders + myRepList
    def uniqueProviders = allProviders.unique { it.id }
    def finalChangesMap = [
        "data_provider": uniqueProviders
    ]
    workItems << [tId: tId, changes: finalChangesMap]
}
log.info("[AS DQLA] items to update = ${workItems.size()}")

// 2) Обновление объектов параллельно.
//    Каждый tId независим, поэтому выполняем их в пуле потоков.
def TStartLoop = new Date()
def workerCount = Math.max(1, Math.min(4, workItems.size())) // можно подстроить
def executor = java.util.concurrent.Executors.newFixedThreadPool(workerCount)
def failures = new java.util.concurrent.ConcurrentLinkedQueue()
try {
    def futures = workItems.collect { item ->
        executor.submit({
            try {
                def ch = instanceService.getInstance(item.tId)
                def migrationMode = (ch?.typedAttrByName(InstanceService.STATUS_FIELD) == '70')
                def updateParams = [
                    "baseUrl": baseUrl,
                    "token": token,
                    "businessId": item.tId,
                    "objectType": "DQImplementation",
                    "changesDct": item.changes,
                    "version": ch.version,
                    "migrationMode": migrationMode
                ]
                runAsService.runAsAdmin("UpdateProcedure", ch, updateParams)
            } catch (Throwable e) {
                log.error("[AS DQLA] error for tId = ${item.tId}: ${e.message}", e)
                failures << item.tId
            }
        } as java.util.concurrent.Callable)
    }
    futures.each { it.get() } // дожидаемся завершения всех задач
} finally {
    executor.shutdown()
}
def TendLoop = new Date()
log.info("[AS DQLA] update loop time = ${TimeCategory.minus(TendLoop, TStartLoop)}")
if (!failures.isEmpty()) {
    log.error("[AS DQLA] failed tIds = ${failures.toList()}")
}
def TendUpdImpls = new Date()
log.info("END OF UPD IMPLS")
log.info ("UPD IMPLS time delta = ${TimeCategory.minus(TendUpdImpls, TStartUpdImpls)}")
