import datasapience.gbapiextension.custom.actions.CustomScriptActionType
import datasapience.gbapiextension.core.model.ClassInstance
import datasapience.gbapiextension.custom.actions.GroovyScriptResult
import datasapience.gbapiextension.core.service.InstanceService
import datasapience.gbapiextension.core.model.ReferenceValue
import datasapience.gbapiextension.core.exception.ValidationError
import datasapience.gbapiextension.core.exception.ValidationErrorType
import org.json.JSONObject
import com.fasterxml.jackson.databind.ObjectMapper
import datasapience.gbapiextension.core.exception.InvalidGroovyScriptException
import java.util.logging.Logger
import java.text.SimpleDateFormat
import datasapience.gbapiextension.core.service.InstanceService


Logger getLOG() { java.util.logging.Logger.getLogger('') }
def isNotEmtyFieldsErrors = false
dl_errorsDQ = []
// def uflg = [value: false]
def update_flg = [value: false] // флаг карточки ФСД

/* =============== */
/* === Helpers === */
/* =============== */

// Только для того, что реально приходит как ReferenceValue (из instance.attrByName и т.п.)
Map refToJson(ReferenceValue rv) {
    if (rv == null) return null
    return [
        type: rv.className,
        id: rv.id?.toString(),
        name: rv.name,
        relation: [
            type: rv.relation?.name ?: '',
            description: rv.relation?.description ?: ''
        ]
    ]
}

// Универсальный?
Object attrToJson(Object v) {
    if (v == null) return null
    if (v instanceof ReferenceValue) return refToJson(v)
    if (v instanceof List) return v.collect { attrToJson(it) }
    if (v instanceof Map)  return v.collectEntries { k, val -> [(k): attrToJson(val)] }
    return v
}

class AttrBag {
    String className
    Long businessId
    Map<String, Object> attrs = [:]

    AttrBag(String className, Long businessId = null) {
        this.className = className
        this.businessId = businessId
    }

    // Сырое значение (ReferenceValue, список и т.п.) — сериализуется при send
    void put(String key, Object value) {
        attrs[key] = value
    }

    // Уже готовый JSON-совместимый Map для ReferenceValue — без лишней обёртки
    void putRef(String key, String type, Object id, String name, String relType = '', String relDesc = '') {
        attrs[key] = [[
            type: type,
            id: id?.toString(),
            name: name,
            relation: [type: relType, description: relDesc]
        ]]
    }

    // Готовый список ссылок
    void putRefList(String key, List<Map> refs) {
        attrs[key] = refs
    }

    Map toJson() {
        def out = [
            className: className,
            attrs: attrs.collectEntries { k, v -> [(k): attrToJson(v)] }
        ]
        if (businessId != null) out.businessId = businessId.toString()
        return out
    }

    /**
     * @param targetInst  инстанс, который обновляем (DQImplementation / DQLA / DQIndicator / FSD)
     * @param ctx         контекст: [baseUrl: ..., token: ...]
     */
    void send(def targetInst, Map ctx) {
        if (!attrs) return

        def changesDct = attrs.collectEntries { k, v -> [(k): attrToJson(v)] }

        Integer status = null
        try {
            status = targetInst?.typedAttrByName(InstanceService.STATUS_FIELD) as Integer
        } catch (e) { status = null }
        boolean migrationMode = (status == 70)

        def updateParams = [
            "baseUrl":       ctx.baseUrl,
            "token":         ctx.token,
            "businessId":    targetInst.businessId,
            "objectType":    className,
            "changesDct":    changesDct,
            "version":       targetInst.version,
            "migrationMode": migrationMode
        ]

        runAsService.runAsAdmin("UpdateProcedure", targetInst, updateParams)

        LOG.info("=== Обновлён ${className} (id=${targetInst.businessId}), " +
                 "migrationMode=${migrationMode}, полей: ${attrs.size()} ===")
    }
}

// Хелпер для быстрого создания одного ref-map'а (без ReferenceValue)
Map refMap(String type, Object id, String name, String relType = '', String relDesc = '') {
    return [
        type: type,
        id: id?.toString(),
        name: name,
        relation: [type: relType, description: relDesc]
    ]
}

/* ============================================== */
/* === Базовые функции поиска и работы с БД  ==== */
/* ============================================== */

def getObjByName(String obj_name, String obj_type, int parent) {
    try {
        if (obj_name?.trim() == '') {
            throw new InvalidGroovyScriptException("Empty value for obj_name (parent: $parent)")
        }
        String sql_stmt = """
            SELECT business_id, name, json_obj ->> 'isTechField' AS tech 
            FROM object_data_table odt
            WHERE 1 = 1
            AND UPPER(name) = ?
            AND odt.object_type = ?
                AND is_active
            AND parent = ?
            """
        def resSql = jdbcTemplate.queryForList(sql_stmt, obj_name.toUpperCase().trim(), obj_type, parent)
        return resSql[0]
    } catch (InvalidGroovyScriptException e) {
        throw e
    } catch (Exception e) {
        StringWriter sw = new StringWriter()
        PrintWriter pw = new PrintWriter(sw)
        e.printStackTrace(pw)
        String sStackTrace = sw.toString()
        System.err.println("Error occurred: ${e.message}\nStackTrace:\n$sStackTrace")
        throw new Exception("An error occurred: ${e.message}", e)
    }
}

/* ===================== */
/* === Simple helpers == */
/* ===================== */

boolean hasRefs(def inst, String field) {
    def v = inst?.attrByName(field)
    return v != null && v[0] != null
}

String getUserId(String login) {
    return jdbcTemplate.queryForObject(
        """select business_id from object_data_table odt where object_type ='User'
        and json_obj?'login' and is_active and json_obj ->>'login' = '${login}'""",
        String.class
    )
}

String getLastUserAction(int businessId, String action) {
    return jdbcTemplate.queryForObject(
        'select otp.get_last_user_action(?, ?)',
        [businessId, action] as Object[],
        String.class
    )
}

void PutCreatorToTechOwner(def instance, Map apiCtx) {
    def lastUser = getLastUserAction(instance.getBusinessId().toInteger(), '$process.action.create')
    if (lastUser) {
        Integer userId = getUserId(lastUser)?.toInteger()
        if (!userId) return
        def userRefValtech = new ReferenceValue(
            'User',
            userId,
            lastUser,
            new ReferenceValue.Relation('TECH_OWNER', '')
        )
        def bag = new AttrBag(instance.className, instance.businessId)
        bag.put('tech_owner', userRefValtech)

        to = instanceService.getInstance(userId)
        if (hasRefs(to, "userAgile")) {
            bag.put('BrdTeam2', to.attrByName('userAgile'))
        }
        bag.send(instance, apiCtx) // TODO:
    }
}

/* ===================================================== */
/* === Блок функций для публикации индикаторов DQLA ==== */ // Не используется
/* ===================================================== */

def go_to_start(inst) {
  current_status = inst.attrByName('status')
  if (current_status =='70') {
    instanceService.update(inst.businessId, inst)
  } else if (current_status =='10') {
    return
  } else {
    process = processService.getCurrentProcessContext(inst.businessId).get();
    processService.doTransition(process.getTaskId(), "2", "Изменение DQLA");
  }
}

def go_to_dqla_status(inst, status) {
    mapping = ['10':0, '30':1, '50':2, '70':3]
    dqla_status_steps = mapping[status]
    if (!dqla_status_steps) { return }
    try {
        tmp =[]
        dqla_status_steps.times { iteration ->
          process = processService.getCurrentProcessContext(inst.businessId).get();
          processService.doTransition(process.getTaskId(), "1", "Изменение DQLA");
          tmp.add(process)
        }
        return tmp
    } catch(e) {
        return []
    }
}

def return_dqla_status(inst, status) {
    mapping = ['10':0, '30':1, '50':2, '70':3]
    dqla_status_steps = mapping[status]
    LOG.info("status, dqla_status_steps" + status + " "+ dqla_status_steps)
    if (!dqla_status_steps) { return }
    try {
        tmp =[]
        dqla_status_steps.times { iteration ->
          LOG.info(iteration + "Итерация")
          process = processService.getCurrentProcessContext(inst.businessId).get();
          String trans = '1'
          if (iteration > 0) {trans = '2'} else {trans = '1'}
          processService.doTransition(process.getTaskId(), trans, "Возвращение к прошлому статусу DQLA");
          tmp.add(process)
        }
        return tmp
    } catch(e) {
        return []
    }
}

def main_stream_70_dqind (inst) {
    String currentStatus = ''
    try {
        currentStatus = inst.typedAttrByName(InstanceService.STATUS_FIELD)
    } catch(e) { currentStatus = '10' }
    lst = instanceService.getChildHierarchy(inst.businessId, 1000, 0, '', false)
    for (i in lst) {
        child = instanceService.getInstance(i.businessId)
        go_to_start(child)
        go_to_dqla_status(child, "70")
    }
    return_dqla_status(inst, currentStatus)
}

/* ===================================================== */
/* ===             Синхронизация DQ с FSD           ==== */
/* ===================================================== */
/**
 * Ищет DQImplementation и DQLA по имени, синхронизирует их с FSD.
 *
 * @param instance  карточка FSD
 * @param uf        флаг "что-то менялось" (для main) // отсутствует
 * @param dqf       флаги value_imp / value_dqla
 * @return суффикс имени DQLA (': team') — если DQLA не найден,
 *         но уже понятно, что создавать надо с суффиксом. Иначе ''.
 */

def SyncDQ(instance, dqf) {
    int CATALOG_FOLDER_ID = 9
    int IMPL_PARENT_ID = 2181090    // change
    int DQLA_PARENT_ID = 16004063   // change

    def name1 = instance.attrByName('Schema')?.toString()?.toLowerCase()
    def name2 = instance.attrByName('Dm_code')?.toString()?.toLowerCase()

    // boolean uf = false

    def implInst = null
    def dqlaInst = null
    String inst_tech_team = ''

    // ---------- 1. Поиск DQImplementation по имени ----------
    String implName = name1 + "." + name2 + ": Проверка на уникальность первичного ключа"
    def implBuf = getObjByName(implName, "DQImplementation", IMPL_PARENT_ID)
    if (implBuf) {
        implInst = instanceService.getInstance(implBuf.business_id)
        dqf.value_imp = true
    }

    // ---------- 2. Поиск DQLA ----------
    // Сначала без суффикса
    def dqlaBuf = getObjByName(name1, "DQLA", DQLA_PARENT_ID)
    if (dqlaBuf) {
        def candidate = instanceService.getInstance(dqlaBuf.business_id)
        if (instance.attrByName('techowner') == candidate.attrByName('representer')) {
            dqlaInst = candidate
            dqf.value_dqla = true
        } else {
            // techowner не совпал — ищем с суффиксом команды
            inst_tech_team = instance.attrByName('fsdTeam')?.name?.getAt(0)
            if (inst_tech_team) {
                def buf2 = getObjByName(name1 + ': ' + inst_tech_team, "DQLA", DQLA_PARENT_ID)
                if (buf2) {
                    dqlaInst = instanceService.getInstance(buf2.business_id)
                    dqf.value_dqla = true
                }
            }
        }
    }

    // ---------- 3. Синхронизация DQImplementation ----------
    if (implInst) {
        String dm_name_dq = instance.attrByName('Dm_code')
        String fsd_db_name_dq = instance.attrByName('Db')
        String dm_schema_name_dq = instance.attrByName('Schema')
        String server_name_dq = instance.attrByName('DbServer')

        def !ffg.value_impdm_server_dq, dm_db_dq, dm_schema_dq, dm_dq
        try {
            dm_server_dq = getObjByName(server_name_dq, 'DatabaseService', CATALOG_FOLDER_ID)
            dm_db_dq = getObjByName(fsd_db_name_dq, 'Database', dm_server_dq.business_id)
            dm_schema_dq = getObjByName(dm_schema_name_dq, 'DatabaseSchema', dm_db_dq.business_id)
            dm_dq = getObjByName(dm_name_dq, 'Table', dm_schema_dq.business_id)
            if (!dm_server_dq || !dm_db_dq || !dm_schema_dq || !dm_dq) {
                throw new InvalidGroovyScriptException('Неверно указано расположение таблицы!')
            }
        } catch (e) {
            throw new InvalidGroovyScriptException('Неверно указано расположение таблицы!!!')
        }

        def result = []
        for (fsd_col in instance.attrByName('tableAttr')) {
            if (fsd_col.Is_pk_field == true) {
                def atrbt = getObjByName(fsd_col.DM_col_name.trim().toUpperCase(), 'TableColumn', dm_dq.business_id)
                if (atrbt) result << atrbt
            }
        }
        if (result.isEmpty()) {
            throw new InvalidGroovyScriptException('Отсутствуют первичные ключи') // ошибка которую никто не увидит
        }

        def srcRefs = result.collect { r -> refMap('TableColumn', r.business_id, r.name, 'SOURCE_IMPL') }
        def pkRefs  = result.collect { r -> refMap('TableColumn', r.business_id, r.name, 'IMPL_INDF') }

        def implBag = new AttrBag(implInst.className, implInst.businessId)
        if (implInst.attrByName('dbObjectLink') != srcRefs) {
            implBag.put('dbObjectLink', srcRefs)
        }
        if (implInst.attrByName('dbObjectLinkReq') != srcRefs) {
            implBag.put('dbObjectLinkReq', srcRefs)
        }
        if (implInst.attrByName('dbObjectLinkPKcol') != pkRefs) {
            implBag.put('dbObjectLinkPKcol', pkRefs)
        }
        if (implBag.attrs) {
            implBag.send(implInst, apiCtx) // TODO: сделать апдейт проверки
            // uf.value = true
        }

        // if (uf.value) {
        //     Integer newStatus = implInst.typedAttrByName(InstanceService.STATUS_FIELD) as Integer
        //     instanceService.update(implInst.businessId, implInst)
        //     if (newStatus == 70) {
        //         def process = processService.getCurrentProcessContext(implInst.businessId).get()
        //         processService.doTransition(process.getTaskId(), "1", "Создано автоматически")
        //         process = processService.getCurrentProcessContext(implInst.businessId).get()
        //         processService.doTransition(process.getTaskId(), "2", "Создано автоматически")
        //     }
        // }
    }

    // ---------- 4. Синхронизация DQLA ----------
    if (dqlaInst) {
        def dqlaBag = new AttrBag(dqlaInst.className, dqlaInst.businessId)
        def techowner = instance.attrByName('techowner')
        def analyst = instance.attrByName('analyst')

        if (techowner != null && techowner != dqlaInst.attrByName('representer')) {
            dqlaBag.put('representer', techowner)
        }
        if (analyst != null && analyst != dqlaInst.attrByName('business_steward')) {
            dqlaBag.put('business_steward', analyst)
        }
        if (dqlaBag.attrs) {
            dqlaBag.send(dqlaInst, apiCtx) // TODO: апдейт DQLA
            // uf.value = true
        }
        // (в исходном SyncAll тут был update + doTransition)
    }

    // ---------- 5. Возврат ----------
    return [
        suffix:   (!dqlaInst && inst_tech_team) ? ': ' + inst_tech_team : '',
        implInst: implInst,
        dqlaInst: dqlaInst
    ]
}

/* ===================================================== */
/* ===             Создание проверки КД             ==== */
/* ===================================================== */

def create_unic(instance) {
    DQImplementationToCreate = instanceService.buildNewClassInstance('DQImplementation')
    parent = instanceService.getInstance(2181090)                                           // change
    DQImplementationToCreate.setReference('parent', parent, '')

    int catalog_folder_id = 9

    def name1 = instance.attrByName('Schema')?.toString()?.toLowerCase()
    def name2 = instance.attrByName('Dm_code')?.toString()?.toLowerCase()
    full_name = name1 + "." + name2 + ": Проверка на уникальность первичного ключа"

    // === name оставляем через replaceAttr (проверка уникальности) ===
    try {
        DQImplementationToCreate.replaceAttr('name', full_name)
    } catch (e) {
        throw new InvalidGroovyScriptException('Проверка КД с таким названием уже существует')
    }

    // === Bag, всё хардкодное — сразу Map ===
    def implBag = new AttrBag('DQImplementation')
    implBag.put('linkedBusinessRule', [refMap(
        'DQBusinessRule', 2781619,                                                          // change
        'Уникальность первичного ключа набора данных', 'BP_IMPL')])
    implBag.put('dq_period_cron', '00 09 * * *')
    implBag.put('dq_error_text', 'Первичный ключ не уникален')
    implBag.put('owner', [refMap(
        'User', 1241364,                                                                    // change
        'Арифи Антон (a.arifi@otpbank.ru)', 'DATASET_OWNER')])
    implBag.put('Creation_method', 'Автоматическая генерация')
    implBag.put('BrdTeam1', [refMap(
        'AgileStructure', 1811487, 'Data Office', 'BRD_TEAM')])                             // change
    implBag.put('dq_type', 'unique')

    // --- Таблица ---
    String dm_name_dq      = instance.attrByName('Dm_code')
    String fsd_db_name_dq  = instance.attrByName('Db')
    String dm_schema_name_dq = instance.attrByName('Schema')
    String server_name_dq  = instance.attrByName('DbServer')

    def dm_server_dq, dm_db_dq, dm_schema_dq, dm_dq
    try {
        dm_server_dq = getObjByName(server_name_dq, 'DatabaseService', catalog_folder_id)
        dm_db_dq     = getObjByName(fsd_db_name_dq, 'Database', dm_server_dq.business_id)
        dm_schema_dq = getObjByName(dm_schema_name_dq, 'DatabaseSchema', dm_db_dq.business_id)
        dm_dq        = getObjByName(dm_name_dq, 'Table', dm_schema_dq.business_id)
        if (!dm_server_dq || !dm_db_dq || !dm_schema_dq || !dm_dq) {
            throw new InvalidGroovyScriptException('Неверно указано расположение таблицы!')
        }
    } catch (e) {
        throw new InvalidGroovyScriptException('Неверно указано расположение таблицы!')
    }

    def par = instanceService.getInstance(dm_schema_dq.business_id)
    def t   = instanceService.getInstance(dm_dq.business_id)
    t.setReference('parent', par, '')

    // checking_source — идёт в два поля, делаем один Map и переиспользуем
    def checkingSrcMap = refMap('Table', dm_dq.business_id, dm_dq.name, 'SOURCE_IMPL')
    implBag.put('checking_source', [checkingSrcMap])
    implBag.put('dbTable',        [checkingSrcMap])

    // --- PK-колонки ---
    def pkRows = []
    for (fsd_col in instance.attrByName('tableAttr')) {
        if (fsd_col.Is_pk_field == true) {
            def atrbt = getObjByName(fsd_col.DM_col_name.trim().toUpperCase(),
                                     'TableColumn', dm_dq.business_id)
            if (atrbt) pkRows << atrbt
        }
    }
    if (pkRows.isEmpty()) {
        throw new InvalidGroovyScriptException('Отсутствуют первичные ключи') // ошибка которую никто никогда не увидит
    }

    // Два списка — сразу Map'ы
    def srcRefs = pkRows.collect { r -> refMap('TableColumn', r.business_id, r.name, 'SOURCE_IMPL') }
    def pkRefs  = pkRows.collect { r -> refMap('TableColumn', r.business_id, r.name, 'IMPL_INDF') }
    implBag.put('dbObjectLink',    srcRefs)
    implBag.put('dbObjectLinkReq', srcRefs)
    implBag.put('dbObjectLinkPKcol', pkRefs)

    // --- Создание и статусы ---
    def copiedBusinessId
    try {
        copiedBusinessId = instanceService.create(DQImplementationToCreate)
    } catch (e) {
        throw new InvalidGroovyScriptException('Проверка КД с таким названием уже существует')
    }
    def new_instance = instanceService.getInstance(copiedBusinessId.businessId)
    PutCreatorToTechOwner(new_instance)
    instanceService.update(copiedBusinessId.businessId, new_instance)

    // === Отправляем JSON (заглушка) ===
    implBag.send(new_instance, apiCtx) // TODO: отправка проверки

    def process = processService.getCurrentProcessContext(new_instance.businessId).get()
    processService.doTransition(process.getTaskId(), "1", "Создано автоматически")
    process = processService.getCurrentProcessContext(new_instance.businessId).get()
    processService.doTransition(process.getTaskId(), "2", "Создано автоматически")

    return new_instance
}


/* ===================================================== */
/* ===           Создание DQLA + индикатора         ==== */
/* ===================================================== */

def CrateDqlaAndIndicator(instance, impl_inst, l_name){
   // if (errorsDQ) { throw new InvalidGroovyScriptException("Ошибки при создании части dq") }

    int CATALOG_FOLDER_ID = 9                                                                   // change
   // String DESCRIPTION = """Настоящее соглашение устанавливает технические
   // требования к качеству данных, формируемых в составе \"Название Бизнес требования\"
   //         (\"Название Функционального требования\")"""
   bp = instanceService.getInstance(35010871)                                                   // change
   // BUSINESS_PROCESS = new ReferenceValue(
   //     'BusinessProcess', bp.businessId, bp.name,
   //     new ReferenceValue.Relation('DQLA_BP', '')
   // )
   // int DQLA_FREQ = 60
   // float TERM_RESOLVE = 5.0
   // CONSUMER = new ReferenceValue(
   //     'User', 1241364, 'Арифи Антон (a.arifi@otpbank.ru)',
   //     new ReferenceValue.Relation('DATACONSUMER', '')
   // )
   // TEAM = new ReferenceValue(
   //     'AgileStructure', 1811487, 'Data Office',
   //     new ReferenceValue.Relation('BRD_TEAM', '')
   // )
    String DQI_FORMAT = "rel"
    String DQI_CALC_METHOD = "average"
    float CEL_KD = 100.0
    float DOP_KD = 100.0
    int parent_folder_id = 16004063                                                             // change

    parent = instanceService.getInstance(parent_folder_id)

    // def my_name   = instance.attrByName('name')
    def techowner = instance.attrByName('techowner')
    def analyst   = instance.attrByName('analyst')
    def fsdTeam   = instance.attrByName('fsdTeam')
    name1 = instance.attrByName('Schema')?.toString().toLowerCase()
    name2 = instance.attrByName('Dm_code')?.toString().toLowerCase()

    /* ================= DQLA ================= */
    DQLAToCreate = instanceService.buildNewClassInstance('DQLA')
    DQLAToCreate.setReference('parent', parent, '')

    def dqlaBag = new AttrBag('DQLA')
    dqlaBag.put('consumer', [refMap(
        'User', 1241364, 'Арифи Антон (a.arifi@otpbank.ru)', 'DATACONSUMER')])                  // change
    dqlaBag.put('BrdTeam2', [refMap(
        'AgileStructure', 1811487, 'Data Office', 'BRD_TEAM')])                                 // change
    dqlaBag.put('term_resolve', 5.0)
    dqlaBag.put('dqla_frequency', 60)
    dqlaBag.put('businessprocess', [refMap(
        'BusinessProcess', bp.businessId, bp.name, 'DQLA_BP')])
    dqlaBag.put('description', "Настоящее соглашение устанавливает технические требования к уникальности первичного ключа в схеме " + name1 + ".")
    if (techowner) dqlaBag.put('representer', techowner)          // сырой ReferenceValue
    if (fsdTeam) {
        dqlaBag.put('BrdTeam1', [refMap(
            'AgileStructure', fsdTeam.id[0], fsdTeam.name[0], 'BRD_TEAM')])
    }
    if (analyst) dqlaBag.put('business_steward', analyst)          // сырой ReferenceValue

    // name — через replaceAttr
    try {
        DQLAToCreate.replaceAttr('name', name1 + l_name)
    } catch (e) {
        throw new InvalidGroovyScriptException('DQLA с таким названием уже существует')
    }

    def tbl  = impl_inst.attrByName('checking_table')
   // def sogl = new ReferenceValue(
   //     'Table', tbl.id[0], tbl.name[0],
   //     new ReferenceValue.Relation('SOGL_DQLA', '')
   // )
    dqlaBag.put('sogl', [refMap('Table', tbl.id[0], tbl.name[0], 'SOGL_DQLA')])

    def copiedBusinessId
    try {
        copiedBusinessId = instanceService.create(DQLAToCreate)
    } catch (e) {
        throw new InvalidGroovyScriptException('DQLA с таким названием уже существует')
    }
    def new_instance = instanceService.getInstance(copiedBusinessId.businessId)

    dqlaBag.send(new_instance, apiCtx) // TODO: отправка DQLA

    // instanceService.update(copiedBusinessId.businessId, new_instance) // Тут вроде не нужен апдейт

    /* ================= DQIndicator ================= */
    DQIndicaorToCreate = instanceService.buildNewClassInstance('DQIndicator')
    DQIndicaorToCreate.setReference('parent', new_instance, '')

    def dqiBag = new AttrBag('DQIndicator')
    dqiBag.put('dqi_period', '00 10 * * *')
    dqiBag.put('dqi_format', 'rel')
    dqiBag.put('dqi_calc_method', 'average')
    dqiBag.put('boundCondition2', 100.0)
    dqiBag.put('boundCondition1', 100.0)

    // name — через replaceAttr
    try {
        DQIndicaorToCreate.replaceAttr('name', "Уникальность первичного ключа")
    } catch (e) {
        throw new InvalidGroovyScriptException('Индикатор с таким названием уже существует')
    }

    // dqImplementations — список Map'ов с ReferenceValue внутри
    dqiBag.put('dqImplementations', [[
        dqImplementation: refMap('DQImplementation', impl_inst.businessId,
                                 impl_inst.name, 'INDK_IMPL'),
        dqImplementationWeight: 1.0
    ]])

    def copiedBusinessId2
    try {
        copiedBusinessId2 = instanceService.create(DQIndicaorToCreate)
    } catch (e) {
        throw new InvalidGroovyScriptException('Индикатор с таким названием уже существует')
    }
    def new_instance2 = instanceService.getInstance(copiedBusinessId2.businessId)

    dqiBag.send(new_instance2, apiCtx) // TODO: Отправка индикатор

    // instanceService.update(copiedBusinessId2.businessId, new_instance2)

    LOG.info("Публикуем DQLA")
    def process = processService.getCurrentProcessContext(new_instance.businessId).get()
    processService.doTransition(process.getTaskId(), "1", "Создано автоматически")
    process = processService.getCurrentProcessContext(new_instance.businessId).get()
    processService.doTransition(process.getTaskId(), "2", "Создано автоматически")

    // а индикатор не публикуем?

    return [new_instance.businessId, impl_inst.businessId, new_instance2.businessId]
}

/* ===================================================== */
/* ===                    MAIN                      ==== */
/* ===================================================== */

LOG.info("\n=====================\n= START CrImpl&DQLA =\n=====================")
/* ---------- 1. Валидация обязательных полей ---------- */
try {
    def isNotEmptyFields = [
        'Schema':   'Необходимо заполнить поле Схема набора данных',
        'Dm_code':  'Необходимо заполнить поле Наименование набора данных',
        'Db':       'Необходимо заполнить поле Каталог набора данных',
        'DbServer': 'Необходимо заполнить поле Сервер размещения набора данных',
        'techowner':'Необходимо заполнить поле Технический Владелец',
        'fsdTeam':  'Необходимо заполнить поле Agile команда'
    ]
    for (entry in isNotEmptyFields.entrySet()) {
        def val = instance.typedAttrByName(entry.key)
        if (val == null || val.isEmpty()) {
            dl_errorsDQ.add(entry.value)
            isNotEmtyFieldsErrors = true
        }
    }

    // Хотя бы один PK
    boolean flg = true
    for (fsd_col in instance.attrByName('tableAttr')) {
        if (fsd_col.Is_pk_field == true) { flg = false }
    }
    if (flg) {
        dl_errorsDQ.add("Необходим хотя бы один PK в атрибутах таблицы")
        isNotEmtyFieldsErrors = true
    }



    // Записываем ошибки на карточку (оставляем replaceAttr — это поле UI)
    if (dl_errorsDQ != []) {
        def jsonObj = new JSONObject()
        jsonObj.put('errorsDQ', dl_errorsDQ.toString())
        instance.replaceAttr('errorsDQ', jsonObj.toString()) // Единственное место где мы меняем карточку ФСД
    } else {
        instance.replaceAttr('errorsDQ', '')    // тут тоже
    }
    update_flg.value = true // всегда апдейтим ошибки после валидации //. FIXME: сделать что бы не всегда апдейтилось

    LOG.info("\n\tEnd of validation block\t\tResults:\n" +
             "\t\tisNotEmtyFieldsErrors = ${isNotEmtyFieldsErrors}\n" +
             "\t\terrorsDQ = ${dl_errorsDQ}")
} catch (Exception e) {
    throw new Exception(e.toString())
}

/* ---------- 2. Поиск + синхронизация DQ ---------- */

final String API_BASE_URL = "https://dg.otpbank.ru"

def apiCtx = [baseUrl: API_BASE_URL, token: null]

if (!isNotEmtyFieldsErrors) {
    apiCtx.token = runAsService.runAsAdmin("GetTokenProcedure", instance, [
        "baseUrl":      API_BASE_URL,
        "username":     "bgadmin",
        "password":     "BgAdmin!",
        "clientId":     "Business-glossarium-client",
        "clientSecret": "",
        "disableSsl":   true
    ])
    LOG.info("Токен получен")
}


def ffg = [value_imp: false, value_dqla: false]
def syncRes = [suffix: '', implInst: null, dqlaInst: null]

if (!isNotEmtyFieldsErrors) {
    syncRes = SyncDQ(instance, ffg)
}

LOG.info("SyncDQ results: value_imp=${ffg.value_imp}, value_dqla=${ffg.value_dqla}, " +
         "suffix='${syncRes.suffix}'")

/* ---------- 3. Создание недостающих объектов ---------- */
if (!isNotEmtyFieldsErrors) {

    if (!ffg.value_imp && !ffg.value_dqla) {
        /* --- Ни Проверки, ни DQLA --- */
        LOG.info("Не созданы ни Проверка КД, ни DQLA")
        def impl_inst = create_unic(instance)
        def i = CrateDqlaAndIndicator(instance,
                                      impl_inst, syncRes.suffix)
        // instance.replaceAttr('DQLAId', ...) и т.п. — УБРАНО, полей больше нет
        // update_flg.value = true

    } else if (!ffg.value_imp) {
        /* --- DQLA есть, Проверки нет --- */
        LOG.info("У нас создан DQLA, но не создана Проверка КД")
        def impl_inst = create_unic(instance)
        def dqlaInst = syncRes.dqlaInst

        // Ищем индикатор по найденному DQLA
        def buf = getObjByName("Уникальность первичного ключа",
                               "DQIndicator", dqlaInst.businessId)
        if (!buf) {
            throw new InvalidGroovyScriptException(
                'По DQLA к данной схеме отсутствует Индикатор') // FIXME: если нет индикатора, то его надо создать
        }
        def a_id = buf.business_id
        def a = instanceService.getInstance(a_id)

        // Добавляем проверку в индикатор
        def dqimplemetation = refMap('DQImplementation',
                                     impl_inst.businessId, impl_inst.name, 'INDK_IMPL')
        def entry = [
            dqImplementation: dqimplemetation,
            dqImplementationWeight: 1.0
        ]

        def dqiBag = new AttrBag('DQIndicator', a_id)
        def a_tbl = a.attrByName('dqImplementations')
        if (a_tbl) {
            a_tbl.add(entry)
            dqiBag.put('dqImplementations', a_tbl)
        } else {
            dqiBag.put('dqImplementations', [entry])
        }
        dqiBag.send(a, apiCta, apiCtxx) // TODO: апдейтим индикатор

        LOG.info("Апдейтим индикатор")
        instanceService.update(a_id, a)

        // update_flg.value = true

    } else if (!ffg.value_dqla) {
        /* --- Проверка есть, DQLA нет --- */
        LOG.info("У нас создана Проверка КД, но не создан DQLA")
        def impl_inst = syncRes.implInst
        def i = CrateDqlaAndIndicator(instance,
                                      impl_inst, syncRes.suffix)
        // instance.replaceAttr('DQLAId', ...) — УБРАНО
        // update_flg.value = true

    } else {
        /* --- Всё уже создано --- */
        LOG.info("Всё уже создано ранее")
        // if (update_flg.value) {
        //     instanceService.update(instance.businessId, instance)
        //     update_flg.value = false
        // }
    }
} else { throw new InvalidGroovyScriptException("Необходимо устранить ошибки:\n${dl_errorsDQ}") }

/* ---------- 4. Финальный апдейт FSD ---------- */
if (update_flg.value) {
    instanceService.update(instance.businessId, instance)
}

LOG.info("\n================\n= END SCRIPT =\n================")

return new GroovyScriptResult(CustomScriptActionType.REFRESH, null, 'result')
