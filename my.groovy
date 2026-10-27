/* groovylint-disable LineLength, MethodName, MethodParameterTypeRequired, MethodReturnTypeRequired, NestedBlockDepth, ParameterCount, ParameterName, VariableName */
import datasapience.gbapiextension.core.model.ClassInstance
import datasapience.gbapiextension.core.model.ReferenceValue
import datasapience.gbapiextension.core.mapper.ClassInstanceMapper
import datasapience.gbapiextension.core.mapper.MetaClassMapper
import datasapience.gbapiextension.core.exception.ValidationError
import datasapience.gbapiextension.core.exception.ValidationErrorType
import datasapience.gbapiextension.custom.actions.GroovyScriptResult
import java.util.logging.Logger
import datasapience.gbapiextension.core.GlossaryMode
import datasapience.gbapiextension.core.model.LanguageType
import datasapience.gbapiextension.core.service.InstanceService
import datasapience.gbapiextension.core.exception.InvalidGroovyScriptException
import groovy.transform.Field
import groovy.time.TimeCategory
import java.text.SimpleDateFormat

/**********************************
 * Constants & setup
 **********************************/
// final Set<String> approvalStatuses = ["50","70"] as Set
// final int catalogFolderId = 9 // каталог, где лежат источники // hardcode
Set<String> getApprovalStatuses() { ['50', '70'] as Set }
int getCatalogFolderId() { 9 } // каталог, где лежат источники

// String newStatus  = instance.typedAttrByName(InstanceService.STATUS_FIELD)
// oldVersion = event.getParams()?.get("oldVersion")
String getNewStatus() { instance.typedAttrByName(InstanceService.STATUS_FIELD) }
def getOldVersion() { event.getParams()?.get('oldVersion') }
// Logger LOG = Logger.getLogger('')

// Логгер: getLogger возвращает кешируемый инстанс по имени, создание здесь — ок
Logger getLOG() { java.util.logging.Logger.getLogger('') }

/**********************************
 * Simple helpers
 **********************************/
 /** @return true, если атрибут существует и содержит непустой список ссылок */
boolean hasRefs(def inst, String field) {
    def v = inst?.attrByName(field)
    return v != null && v[0] != null
}
/** @return true, если значение не null, не пустая строка и не пустая коллекция */
boolean hasValue(def v) {
    if (v == null) return false
    if (v instanceof CharSequence) return v.toString().trim() != ''
    if (v instanceof Collection)   return !v.isEmpty()
    return true
}
/** @return список ссылок из атрибута или пустой список */
List getRefs(def inst, String field) {
    def v = inst?.attrByName(field)
    return v ?: []
}
/** @return строка в верхнем регистре, либо пустая при null */
String upper(String s) { s?.toUpperCase() ?: '' }
/** @return текстовое значение {@code attr.text} или null */
String attrText(def inst, String field) { inst?.attrByName(field)?.text }
/** @return true если происходит смена статуса */
boolean changeStatusCheck() {
    String oldStatus = oldVersion?.typedAttrByName(InstanceService.STATUS_FIELD)
    return newStatus != oldStatus
}

/**********************************
 * Validation & errors
 **********************************/
/**
 * Добавляет критическую ошибку валидации в общий список {@code validationErrors}.
 *
 * @param title   системное имя/код поля (attrName)
 * @param label   метка/отображаемое название атрибута
 * @param content текст ошибки для пользователя/логики
 *
 * Побочные эффекты: добавляет {@link ValidationError} в {@code validationErrors}.
 */
def sendValidationError(String title, String label, String content) {
    if (changeStatusCheck()) {
        def validationError = new ValidationError()
        validationError.setAttrName(title)
        validationError.setAttributeLabel(label)
        validationError.addValidationDetail(title, content, ValidationErrorType.CRITICAL)
        validationErrors.add(validationError)
    }
}

/**********************************
 * DB helpers
 **********************************/
/**
 * Вычисляет имя физической таблицы для источника данных.
 * Для DQDBSource использует его {@code tmp_table_name} с префиксом {@code dqstage.}.
 * Для прочих — собирает {@code schema.parent + '.' + table.name} с автоматическим кавычением
 * идентификаторов, не соответствующих паттерну {@code [A-Za-z_][A-Za-z0-9_]*}.
 *
 * @param srcObj объект-источник
 * @return полное имя таблицы/представления для SQL (с кавычками при необходимости)
 */
String resolveTmpTableName(def srcObj) {
    // допустимый идентификатор: snake_case без спецсимволов
    final String IDENT_PATTERN = '^[A-Za-z_][A-Za-z0-9_]*$'

    // локальная функция кавычения
    def quoteIdentIfNeeded = { String name ->
        if (name == null) return null
        // если имя простое — без кавычек
        if (name ==~ IDENT_PATTERN) {
            return name
        }
        // иначе добавляем кавычки и экранируем внутренние "
        return '"' + name.replace('"', '""') + '"'
    }

    // Особый случай для DQDBSource
    if (srcObj.getMetaClassName() == 'DQDBSource') {
        // предполагаем, что tmp_table_name уже содержит table / schema.table как надо
        return "dqstage.${srcObj.attrByName('tmp_table_name')}"
    }

    String schemaName = srcObj.getParent().name
    String tableName  = srcObj.name

    String schemaPart = quoteIdentIfNeeded(schemaName)
    String tablePart  = quoteIdentIfNeeded(tableName)

    return "${schemaPart}.${tablePart}"
}


/**
 * Проверяет корректность SQL-условия объединения источников.
 * Требования: наличие ключевых слов FROM и JOIN, а также псевдонимов @S1 и @S2.
 * При нарушении требований генерирует ошибки через {@code sendValidationError}.
 *
 * @param instance экземпляр, к которому относится проверка (для контекста ошибки)
 * @param sjRaw    текст условия объединения (может быть null/пустой)
 *
 * Побочные эффекты: может добавить несколько ошибок валидации.
 */
void validateJoinCondition(def instance, String sjRaw) {
    if (!hasValue(sjRaw)) {
        sendValidationError(
            'source_join_condition',
            'Не заполнено условие объединения',
            'Укажите условие с FROM/JOIN и используйте @S1/@S2.'
        )
    // return
    }
    def sj = upper(sjRaw)
    if (!sj.contains('FROM')) {
        sendValidationError('source_join_condition','Условия объединения не содержит FROM','Добавьте FROM и используйте @S1/@S2.')
    }
    if (!sj.contains('JOIN')) {
        sendValidationError('source_join_condition','Условия объединения не содержит JOIN','Добавьте JOIN и используйте @S1/@S2.')
    }
    if (!sj.contains('@S1')) {
        sendValidationError('source_join_condition','Нет @S1','Используйте псевдоним @S1.')
    }
    if (!sj.contains('@S2')) {
        sendValidationError('source_join_condition','Нет @S2','Используйте псевдоним @S2.')
    }
}

/**
 * Возвращает SQL-оператор сравнения по коду типа сравнения.
 *
 * Поддерживаемые коды: gt(>), gteq(>=), lt(<), lteq(<=), equal(=), not_equal(!=).
 *
 * @param dq_type_compare код типа сравнения
 * @return строка с оператором или null, если код не распознан
 */
String getCompareTypeSign(String dq_type_compare) {
    if (dq_type_compare == 'gt')        return '>'
    if (dq_type_compare == 'gteq')      return '>='
    if (dq_type_compare == 'lt')        return '<'
    if (dq_type_compare == 'lteq')      return '<='
    if (dq_type_compare == 'equal')     return '='
    if (dq_type_compare == 'not_equal') return '!='
    return null
}

/**
 * Очищает поля, которые не спользуются в одноисточниковых проверках.
 */
void clearUnoTableChecks() {
    instance.replaceAttr('checking_source2', [])
    instance.replaceAttr('checking_table2', [])
    instance.replaceAttr('dbObjectLink2', [])
    instance.replaceAttr('dbObjectLinkPKcol2', [])
    instance.replaceAttr('source_join_condition', new LanguageType('', 'sql'))
}

/**
 * Находит пользователя, сделавшего действие над карточкой.
 *
 * @param businessId Бизнес-ID объекта, над которым совершено действие.
 * @param action Вид действия, совершенного над карточкой.
 */
String getLastUserAction(int businessId, String action) {
    return jdbcTemplate.queryForObject(
        'select otp.get_last_user_action(?, ?)',
        [businessId, action] as Object[],
        String.class
    )
}

/**
 * Находит ID пользователя, по логину.
 *
 * @param login Логин пользователя, над которым совершено действие.
 */
String getUserId(String login) {
    return jdbcTemplate.queryForObject(
        """select business_id from object_data_table odt where object_type ='User'
        and json_obj?'login' and is_active and json_obj ->>'login' = '${login}'""",
        String.class
    )
}

/**********************************
 * Autocomplete & linking
 **********************************/
 /**
 * Возвращает объект метаданных по имени/типу/родителю через прямой SQL (jdbcTemplate).
 *
 * @param obj_name имя искомого объекта (обязательное, непустое)
 * @param obj_type тип (например, 'Table', 'TableColumn', 'DatabaseSchema')
 * @param parent   business_id родителя
 * @return map с полями business_id, name, tech
 * @throws InvalidGroovyScriptException если {@code obj_name} пустое
 * @throws Exception при любых ошибках JDBC (c расширенным stacktrace в stderr)
 */
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
        e.printStackTrace(new PrintWriter(sw))
        System.err.println("Error occurred: ${e.message}\nStackTrace:\n${sw}")
        throw new Exception("An error occurred: ${e.message}", e)
    }
}

/**
 * Автозаполняет поля на вкладке «Проверка качества данных» из связанных разделов.
 * Копирует: dbTable → checking_source, dbObjectLinkReq → dbObjectLink, runScheduleReq1 → dq_period_cron.
 * Также подтягивает PK-колонки из FSD и записывает их в dbObjectLinkPKcol.
 *
 * Побочные эффекты: вызывает replaceAttr над множеством полей экземпляра.
 * Безопасно завершается при отсутствии необходимых исходных данных.
 */
def Autocomplete10() {
    try {
        // String oldStatus = oldVersion?.typedAttrByName(InstanceService.STATUS_FIELD)
        def bd_tbl = instance.attrByName('dbTable')?.with { it ? it[0] : null }
        if (!hasRefs(instance, 'checking_source') && bd_tbl) {
            instance.replaceAttr('checking_source', bd_tbl) // из Требований
        }
        if (!hasRefs(instance, 'dbObjectLink')) {
            def dbAtr = instance.attrByName('dbObjectLinkReq')
            if (dbAtr) instance.replaceAttr('dbObjectLink', dbAtr)
        }
        // if (!hasRefs(instance, 'dq_period_cron')) {
        //     def tmAtr = instance.attrByName('runScheduleReq1')
        //     if (tmAtr) instance.replaceAttr('dq_period_cron', tmAtr)
        // }

        // PK из Функциональных требований → dbObjectLinkPKcol
        if (!hasRefs(instance, 'dbObjectLinkPKcol') && bd_tbl) {
            def bd_tbl_inst = instanceService.getInstance(bd_tbl.id)
            def fsd_atr     = bd_tbl_inst?.attrByName('FSD')
            if (!fsd_atr) return
            def fsd_inst    = instanceService.getInstance(fsd_atr.id)
            def fsdStruct   = fsd_inst?.attrByName('tableAttr')
            if (!fsdStruct) return

            String dm_name_dq      = fsd_inst.attrByName('Dm_code')
            String fsd_db_name_dq  = fsd_inst.attrByName('Db')
            String dm_schema_name  = fsd_inst.attrByName('Schema')
            String server_name_dq  = fsd_inst.attrByName('DbServer')
            if (!dm_name_dq || !fsd_db_name_dq || !dm_schema_name || !server_name_dq) {
                LOG.info('Не указано расположение таблицы! BSImplementation Autocomplete10')
                return
            }

            def dm_server_dq, dm_db_dq, dm_schema_dq, dm_dq
            try {
                dm_server_dq = getObjByName(server_name_dq, 'DatabaseService', catalogFolderId)
                dm_db_dq     = getObjByName(fsd_db_name_dq, 'Database',       dm_server_dq.business_id)
                dm_schema_dq = getObjByName(dm_schema_name,  'DatabaseSchema', dm_db_dq.business_id)
                dm_dq        = getObjByName(dm_name_dq,      'Table',          dm_schema_dq.business_id)
                if (!dm_server_dq || !dm_db_dq || !dm_schema_dq || !dm_dq) {
                    LOG.info('Неверно указано расположение таблицы!!! BSImplementation Autocomplete10')
                    return
                }
            } catch (e) {
                LOG.info('Неверно указано расположение таблицы! BSImplementation Autocomplete10')
                return
            }
            def result = []
            for (fsd_col in fsdStruct) {
                if (fsd_col.Is_pk_field == true) {
                    try {
                        def atrbt = getObjByName(fsd_col.DM_col_name.trim().toUpperCase(), 'TableColumn', dm_dq.business_id)
                        result << atrbt
                    } catch (e) {
                        throw new InvalidGroovyScriptException('Не могу получить название поля, которое является PK')
                    }
                }
            }
            if (!result || result[0] == null) {
                LOG.info('there is no PK keys BSImplementation Autocomplete10')
                return
            }

            // сохранить первый PK
            def pkRefs = new ReferenceValue('TableColumn', result[0].business_id, result[0].name, new ReferenceValue.Relation('IMPL_INDF', ''))
            instance.replaceAttr('dbObjectLinkPKcol', pkRefs)
            def dbObjectLinkPKcols = instance.attrByName('dbObjectLinkPKcol')

            // добавить остальные
            if (result.size() > 1) {
                for (def item : result[1..-1]) {
                    dbObjectLinkPKcols.add(new ReferenceValue('TableColumn', item.business_id, item.name, new ReferenceValue.Relation('IMPL_INDF', '')))
                }
                instance.replaceAttr('dbObjectLinkPKcol', dbObjectLinkPKcols)
            }
        }
    } catch (Exception e) {
        LOG.warning("Autocomplete10 error: ${e.message}")
    }
}

def Autocomplete50(instance) {
    String oldStatus = oldVersion?.typedAttrByName(InstanceService.STATUS_FIELD)
    if ((oldStatus == '30' && newStatus == '50') || (oldStatus == '520' && newStatus == '50') || (oldStatus == '10' && newStatus == '50')) {
        if (!hasRefs(instance, "dbTable")) {
            instance.replaceAttr("dbTable", instance.attrByName("checking_table"))
        }
        if (!hasRefs(instance, "dbObjectLinkReq")) {
            instance.replaceAttr("dbObjectLinkReq", instance.attrByName("dbObjectLink"))
        }
    }
}

def AutocomleteBool() {
    if (!(instance.attrByName('dq_type') in [null, 'other'])) { instance.replaceAttr('standardLogic', true)}
}

def AutocompleteDq_error_text() {
    try {
        if (instance.attrByName("linkedBusinessRule")) {
            if (!instance.attrByName("dq_error_text")) {
                def br_inst = instanceService.getInstance(instance.attrByName("linkedBusinessRule").id[0])
                LOG.info("catched br_inst")
                instance.replaceAttr("dq_error_text", br_inst.attrByName("dq_error_text"))
                LOG.info("replaced")
            }
        }
    } catch (Exception e) {
        LOG.warning("AutocompleteUpdateRun error: ${e.message}")
    }
}

/**
 * Автозаполняет поля на обоих вкладках
 * Если атрибут {@code UpdateRun} = True, то все расписания устанавливаются на "Отсутсвует"
 *
 * Побочные эффекты: вызывает replaceAttr над двумя полями экземпляра.
 * Безопасно завершается при отсутствии необходимых исходных данных.
 */
def AutocompleteUpdateRun(){
    try {
		if (instance.attrByName('dq_run_type') == 'after_update') {
			if (instance.attrByName('UpdateRun')){
				instance.replaceAttr('dq_period_cron', null)
				// instance.replaceAttr('runScheduleReq1', null)
			}
		} else {
			instance.replaceAttr('UpdateRun', false)
		}
    } catch (Exception e) {
        LOG.warning("AutocompleteUpdateRun error: ${e.message}")
    }
}

/**********************************
 * Source links & template mapping
 **********************************/
 /**
 * Связывает экземпляр с таблицей-источником и автоматически выставляет PK-колонки.
 *
 * Алгоритм: для каждого источника из {@code checking_source_field} определяет таблицу
 * (для DQDBSource — через его {@code dbObjectLink}, иначе сам источник = таблица),
 * проставляет ссылку в {@code checking_table_field}, и если {@code dbObjectLinkPKcol_field} пуст —
 * находит все колонки таблицы с признаком первичного ключа и записывает их как ссылки.
 *
 * @param instance                экземпляр, в котором настраиваются связи
 * @param checking_source_field   имя атрибута со списком источников
 * @param checking_table_field    имя атрибута для ссылки на таблицу
 * @param dbObjectLinkPKcol_field имя атрибута для ссылок на PK-колонки
 *
 * Побочные эффекты: {@code replaceAttr}/{@code setReference} текущего экземпляра.
 */
def setSourceLinks(def instance, String checking_source_field, String checking_table_field, String dbObjectLinkPKcol_field) {
    def checking_source = instance.attrByName(checking_source_field)
    if (checking_source == null || checking_source.isEmpty() || checking_source == [null]) return

    for (checking_src in checking_source) {
        def checking_source_obj = instanceService.getInstance(checking_src.id())
        ClassInstance tbl_inst = null

        if (checking_source_obj.getMetaClassName() == 'DQDBSource') {
            def src_dbObjectLink = checking_source_obj.attrByName('dbObjectLink')
            if (src_dbObjectLink == null || src_dbObjectLink.isEmpty() || src_dbObjectLink == [null]) return
            def srcRef = new ReferenceValue('Table', src_dbObjectLink[0].id, src_dbObjectLink[0].name, new ReferenceValue.Relation('SOURCE_IMPL', ''))
            instance.replaceAttr(checking_table_field, srcRef)
            if ((src_dbObjectLink != null) && (src_dbObjectLink != '') && (src_dbObjectLink[0] != null)) {
                tbl_inst = instanceService.getInstance(src_dbObjectLink[0].id())
            }
        } else {
            tbl_inst = checking_source_obj
            instance.replaceAttr(checking_table_field, checking_source)
        }

        if (tbl_inst != null) {
            List<ClassInstance> columns = instanceService.getInstancesByParent(
                tbl_inst.getBusinessId(), 1000, 0, 'name', 'asc', GlossaryMode.WORKPLACE)

            def pk_cols = instance.attrByName(dbObjectLinkPKcol_field)
            if (pk_cols == null || pk_cols == '' || pk_cols == '[]' || pk_cols.size() == 0) {
                pk_cols = null
                for (def col : columns) {
                    if (col.attrByName('is_primary_key')) {
                        if (pk_cols == null) {
                            instance.setReference(dbObjectLinkPKcol_field, col, '')
                            pk_cols = instance.attrByName(dbObjectLinkPKcol_field)
                        } else {
                            pk_cols.add(new ReferenceValue('TableColumn', col.getBusinessId(), col.getName(), new ReferenceValue.Relation('LINK', '')))
                            pk_cols = pk_cols.unique()
                        }
                    }
                }
                if (pk_cols != null) {
                    instance.replaceAttr(dbObjectLinkPKcol_field, pk_cols)
                }
            }
        }
    }
}

/**
 * По параметрам шаблона выставляет «проверяемые колонки» в {@code dbObjectLink_field}.
 * Для источника находит связанную таблицу, её колонки и для каждого параметра из {@code params}
 * ищет колонку с совпадающим именем. При успехе добавляет ссылку на колонку, при неуспехе — валидирует.
 *
 * @param instance              текущий экземпляр
 * @param checking_source_field имя атрибута с источниками (S1)
 * @param checking_table_field  имя атрибута для таблицы (будет проставлен из источника)
 * @param dbObjectLink_field    имя атрибута, куда добавлять найденные колонки
 * @param params                список параметров шаблона (ожидаются поля templateParamKey/Value)
 * @return 1, если все параметры сопоставлены; 0, если хотя бы один не найден
 *
 * Побочные эффекты: {@code replaceAttr}/{@code setReference}; при ошибках — {@code sendValidationError}.
 */
int setCheckedDbObjectByTemplate(def instance, String checking_source_field, String checking_table_field, String dbObjectLink_field, def params) {
    def checking_source = instance.attrByName(checking_source_field)
    int res = 1
    if (checking_source == null) return res

    for (checking_src in checking_source) {
        def checking_source_obj = instanceService.getInstance(checking_src.id())
        def src_dbObjectLink = checking_source_obj.attrByName('dbObjectLink')
        if ((src_dbObjectLink != null) && (src_dbObjectLink != '') && (src_dbObjectLink[0] != null)) {
            instance.replaceAttr(checking_table_field, src_dbObjectLink)
            ClassInstance tbl_inst = instanceService.getInstance(src_dbObjectLink[0].id())
            List<ClassInstance> columns = instanceService.getInstancesByParent(src_dbObjectLink[0].id(), 1000, 0, 'name', 'asc', GlossaryMode.WORKPLACE)
            def CheckedDbObject = null
            for (p in params) {
                boolean found = false
                for (def col : columns) {
                    if (col.getName().toUpperCase() == p.templateParamValue.toUpperCase()) {
                        found = true
                        if (CheckedDbObject == null) {
                            instance.setReference(dbObjectLink_field, col, '')
                            CheckedDbObject = instance.attrByName(dbObjectLink_field)
                        } else {
                            CheckedDbObject.add(new ReferenceValue('TableColumn', col.getBusinessId(), col.getName(), new ReferenceValue.Relation('CHECK', '')))
                            CheckedDbObject = CheckedDbObject.unique()
                        }
                    }
                }
                if (!found) {
                    sendValidationError('templateParamValue',
                        "Колонка ${p.templateParamValue}, указанная в параметрах, отсутствует в проверяемом источнике данных",
                        'Укажите корректное имя колонки')
                    res = 0
                }
            }
        }
    }
    return res
}

/**********************************
 * SQL column helpers
 **********************************/
/**
 * Строит список колонок через запятую в формате {@code table.column}.
 * Для колонок из {@code dbObjectLink}, входящих в PK, добавляет псевдоним {@code AS checked_<col>}.
 *
 * @param instance        экземпляр с ссылками на колонки
 * @param colRefFieldName имя атрибута со ссылками на колонки
 * @param tableName       имя таблицы (префикс)
 * @return строка вида {@code t.c1, t.c2, ...}
 */
String getCommaSeparatedColumns(def instance, String colRefFieldName, String tableName) {
    def res = ''
    def col_list    = instance.attrByName(colRefFieldName)
    def pk_col_list = instance.attrByName('dbObjectLinkPKcol') ?: []
    def pk_names    = pk_col_list.collect { it.name() }

    for (col in col_list) {
        def col_obj  = instanceService.getInstance(col.id())
        def col_name = "${tableName}.${col_obj.name}"
        if (colRefFieldName == 'dbObjectLink' && pk_names.contains(col_obj.name)) {
            col_name += " AS checked_${col_obj.name}"
        }
        res = res ? (res + ', ' + col_name) : col_name
    }
    return res
}
// Список колонок без алиасов — удобно для GROUP BY / WHERE
String getColumnsNoAlias(def instance, String colRefFieldName, String tableName) {
    def res = ''
    def cols = instance.attrByName(colRefFieldName) ?: []
    for (col in cols) {
        def colObj  = instanceService.getInstance(col.id())
        def q       = "${tableName}.${colObj.name}"
        res = res ? (res + ', ' + q) : q
    }
    return res
}

/**
 * Формирует выражение конкатенации значений колонок через разделитель {@code '|'}.
 * Каждый элемент приводится к тексту: {@code table.column::text}.
 * Для PK-колонок из {@code dbObjectLink} добавляет псевдоним {@code AS checked_<col>}.
 *
 * @param instance        экземпляр с ссылками на колонки
 * @param colRefFieldName имя атрибута со ссылками на колонки
 * @param tableName       имя таблицы (префикс)
 * @return SQL-выражение конкатенации, пригодное для SELECT
 */
String getConcatColumns(def instance, String colRefFieldName, String tableName) {
    def res = ''
    def col_list    = instance.attrByName(colRefFieldName)
    def pk_col_list = instance.attrByName('dbObjectLinkPKcol') ?: []
    def pk_names    = pk_col_list.collect { it.name() }

    for (col in col_list) {
        def col_obj  = instanceService.getInstance(col.id())
        def col_name = "${tableName}.${col_obj.name}"
        if (colRefFieldName == 'dbObjectLink' && pk_names.contains(col_obj.name)) {
            col_name += " AS checked_${col_obj.name}"
        }
        // res = res ? (res + " || '|' || " + col_name + '::text') : (col_name + '::text') // было
        res = res ? (res + " || '|' || " + col_name) : (col_name) // стало
    }
    return res
}
/**
 * Формирует пары для JSON-конструкций SQL: {@code 'colName', table.colName}, через запятую.
 * Для PK-колонок из {@code dbObjectLink} добавляет псевдоним {@code AS checked_<col>}.
 *
 * @param instance        экземпляр с ссылками на колонки
 * @param colRefFieldName имя атрибута со ссылками на колонки
 * @param tableName       имя таблицы (префикс)
 * @return строка вида {@code 'c1', t.c1, 'c2', t.c2, ...}
 */
String getCommaSeparatedColumnsJSON(def instance, String colRefFieldName, String tableName) {
    def res = ''
    def col_list    = instance.attrByName(colRefFieldName)
    def pk_col_list = instance.attrByName('dbObjectLinkPKcol') ?: []
    def pk_names    = pk_col_list.collect { it.name() }

    for (col in col_list) {
        def col_obj  = instanceService.getInstance(col.id())
        def col_name = "${tableName}.${col_obj.name}"
        if (colRefFieldName == 'dbObjectLink' && pk_names.contains(col_obj.name)) {
            col_name += " AS checked_${col_obj.name}"
        }
        def pair = "'${col_obj.name}', ${col_name}"
        res = res ? (res + ', ' + pair) : pair
    }
    return res
}
/**
 * Формирует булево условие проверки на NULL для набора колонок:
 * {@code (t.c1 IS NULL OR t.c2 IS NULL ...)}.
 * Для PK-колонок из {@code dbObjectLink} добавляет псевдоним {@code AS checked_<col>}.
 *
 * @param instance        экземпляр с ссылками на колонки
 * @param colRefFieldName имя атрибута со ссылками на колонки
 * @param tableName       имя таблицы (префикс)
 * @return SQL-условие в круглых скобках
 */
String getNotNullCheckColumns(def instance, String colRefFieldName, String tableName) {
    def res = ''
    def col_list    = instance.attrByName(colRefFieldName)
    def pk_col_list = instance.attrByName('dbObjectLinkPKcol') ?: []
    def pk_names    = pk_col_list.collect { it.name() }

    for (col in col_list) {
        def col_obj  = instanceService.getInstance(col.id())
        def col_name = "${tableName}.${col_obj.name}"

        // В WHERE нельзя писать AS, поэтому просто игнорируем
        // alias будем использовать только в SELECT
        res = res ? (res + ' OR ' + col_name + ' IS NULL') : (col_name + ' IS NULL')
    }
    return '(' + res + ')'
}

/**
 * Проверяет наличие PK-колонок в {@code dbObjectLinkPKcol}.
 * Если статус из списка согласования (50/70) и PK не заданы — добавляет ошибку валидации.
 *
 * @param instance  текущий экземпляр
 * @param newStatus статус экземпляра
 * @return 1 — PK заданы; 0 — PK отсутствуют (и при необходимости провалидированы)
 */
int CheckPKColumns(def instance, def newStatus) {
    if (hasRefs(instance, 'dbObjectLinkPKcol')) return 1
    if (newStatus in approvalStatuses) {
        sendValidationError(
            'Не указан Идентификатор записи',
            'Не указан Идентификатор записи',
            'Укажите поле(я) первичного ключа источника данных — требуется для фиксации ошибочных записей.'
        )
    // return 0
    }
    return 0
}

/**********************************
 * Scripts: filter_script
 **********************************/
 /**
 * Копирует скрипт выполнения проверки в скрипт автозакрытия:
 * Пропускает пустые значения.
 */
void copyScriptToFilter(def instance) {
    LOG.info('imhere 2')
    LOG.info("instance.attrByName('filter_script') = ${instance.attrByName('filter_script')}")
    // if (hasRefs (instance, 'filter_script')) { return }
    // if (!hasRefs (instance, 'full_bad_records_script')) { return }
    // if (!instance.attrByName('filter_script')) { return }
    LOG.info("if 0")
    def fscript = instance.attrByName('full_bad_records_script')
    LOG.info("\n\n\n\n filter_script = ${instance.attrByName('filter_script')}\n full_bad_records_script = ${fscript}\n")
    if (fscript) {\
        LOG.info("if 1")
        if (instance.attrByName('filter_script') == null) { instance.replaceAttr('filter_script', fscript) }
        else if (instance.attrByName('filter_script').text == '') {
            LOG.info("if 2")
            instance.replaceAttr('filter_script', fscript)
        }
    }
}

void implSamplUsage(def instance) {
    LOG.info('implSampleUsage Start')
    def br_id = 0
    def resSql = null
    String oldStatus = oldVersion?.typedAttrByName(InstanceService.STATUS_FIELD)
    LOG.info("oldStatus = ${oldStatus}")
    LOG.info("newStatus = ${newStatus}")
    if (oldStatus == '10' && newStatus == '520') {
        br_id = instance.attrByName('linkedBusinessRule')?.id?[0]
        LOG.info("br_id = ${br_id}")
        if (br_id) {
            sql = """
                SELECT o.business_id
                FROM object_data_table o
                JOIN reference_table r ON o.id = r.from_id
                WHERE r.relation_type = 'BP_IMPLSAMPLE'
                  AND r.to_business_id = ?
                LIMIT 1
            """
            try {
                resSql = jdbcTemplate.queryForObject(sql, Long.class, br_id)
            } catch (e) { resSql = null }
        }
        LOG.info("resSql = ${resSql}")
        LOG.info("resSql type = ${resSql.getClass().name}")
        ClassInstance DqImplSampleInstance
        if (resSql) {
            try {
                DqImplSampleInstance = instanceService.getInstance(resSql)
            } catch (e) { DqImplSampleInstance = null }
        }
        if (DqImplSampleInstance) {
            // Описание ошибки // Заполняется автоматически на основе шаблона //
            // instance.replaceAttr('dq_error_text', DqImplSampleInstance.attrByName('error_description'))
            // Тип проверки // Заполняется автоматически на основе шаблона //
            instance.replaceAttr('dq_type', DqImplSampleInstance.attrByName('dq_type'))
            // Тип сравнения // Заполняется автоматически на основе шаблона //
            instance.replaceAttr('dq_type_compare', DqImplSampleInstance.attrByName('dq_type_compare'))
            // Диапазон. Начало // Заполняется автоматически на основе шаблона //
            instance.replaceAttr('intervalStartStr', DqImplSampleInstance.attrByName('interval_start_str'))
            // Диапазон. Окончание // Заполняется автоматически на основе шаблона //
            instance.replaceAttr('intervalFinishStr', DqImplSampleInstance.attrByName('interval_finish_str'))
            // Стандартный скрипт // Заполняется автоматически на основе шаблона //
            // instance.replaceAttr('standardLogic', true)

            // DQImplementationToCreate.replaceAttr('templateParams', DqImplSampleInstance.attrByName('dq_parameter_code2'))

            if (DqImplSampleInstance.attrByName('dq_type') == 'template') {
                template_ref = new ReferenceValue(
                    'DQImplSample',
                    DqImplSampleInstance.businessId,
                    DqImplSampleInstance.name,
                    new ReferenceValue.Relation('LINK', '')
                )
                instance.replaceAttr('check_template', template_ref)
            }
        }
    }
}


/**********************************
 * Business logic: owners, codes
 **********************************/
 /** Legacy
 * Копирует владельцев из связанных объектов требований:
 * {@code dbObjectLinkReq.owner → tech_owner}, {@code dbObjectLinkReq.bizOwner → owner}.
 * Пропускает пустые значения.
 *
 * @param instance экземпляр, куда копируются значения
 *
 * Побочные эффекты: {@code replaceAttr} над полями владельцев.
 */
void copyOwnerAttrsFromReq(def instance) {
    if (!hasRefs(instance, 'dbObjectLinkReq')) return
    for (ref in getRefs(instance, 'dbObjectLinkReq')) {
        def obj = instanceService.getInstance(ref.id())
        def techOwner = obj.attrByName('owner')
        def bizOwner  = obj.attrByName('bizOwner')
        if (hasRefs(obj, 'owner'))    instance.replaceAttr('tech_owner', techOwner)
        if (hasRefs(obj, 'bizOwner')) instance.replaceAttr('owner', bizOwner)
    }
}

/**
 * Проставляет {@code Creation_method} при создании проверки
 * как "Ручное создание". "Автоматическая генерация" проставляется
 * при генерации проверки по кнопке с вкладки "Data Quality"
 * на карточке объекта FSD.
 *
 * @param instance целевой экземпляр
 */
Boolean ensureCreationMethod(def instance) {
    // if (instance.attrByName('Creation_method') == 'Ручное создание ') return false
    if (instance.attrByName('Creation_method')) return true
    instance.replaceAttr('Creation_method', 'Ручное создание')
    return true
}

/**
 * Обеспечивает наличие кодов правила: генерирует и проставляет {@code dq_code} и {@code code}
 * в формате {@code DQ<businessId>}.
 *
 * @param instance целевой экземпляр
 *
 * Побочные эффекты: {@code replaceAttr} полей {@code dq_code} и {@code code}.
 */
void ensureCodes(def instance) {
    def dqCode = 'DQ' + instance.getBusinessId().toString()
    instance.replaceAttr('dq_code', dqCode)
    instance.replaceAttr('code', dqCode)
}

/**********************************
 * LinkGloss (оставлена логика, подчистка)
 **********************************/
 /**
 * Синхронизирует связанные термины глоссария: из {@code dbObjectLinkReq.relatedTerm}
 * добавляет недостающие элементы в {@code linkedBusinessRule.checkedTerm}.
 *
 * @param instance экземпляр, для которого выполняется линковка
 *
 * Побочные эффекты: изменяет атрибуты связанных бизнес-правил и вызывает {@code instanceService.update}.
 * Тихо пропускает отсутствующие/пустые данные.
 */
def LinkGloss (def instance) {
    try {
        def attr_database = instance.attrByName('dbObjectLinkReq') ?: []
        def business_rules = instance.attrByName('linkedBusinessRule') ?: []
        attr_database.each { nxt ->
            def col_bisuness_id = nxt.id
            def column_instance = instanceService.getInstance(col_bisuness_id)
            if (!column_instance) return
            def rel_term = column_instance.attrByName('relatedTerm') ?: []
            if (!rel_term) return

            business_rules.each { ch ->
                def br_instance = instanceService.getInstance(ch.id)
                if (!br_instance) return
                def exists_term = br_instance.attrByName('checkedTerm') ?: []
                boolean added = false
                def toAdd = []
                for (term in rel_term) {
                    if (!exists_term.any { it.id == term.id }) {
                        toAdd.add(term)
                        added = true
                }
            }
                if (added) {
                    def newList = exists_term.plus(toAdd)
                    br_instance.replaceAttr('checkedTerm', newList)
                    instanceService.update(ch.id, br_instance)
                }
        }
    }
    } catch (Exception e) {
        LOG.warning("LinkGloss error: ${e.message}")
}
}

/** // Не используется
 * Вставляет сотрудника создавшего проверку в owner
 *
 * Пропускает пустые значения.
 *
 * @param instance экземпляр, куда копируются значения.
 */
void PutCreatorToOwner(def instance) {
    if (!(newStatus in ['10'])) {
        return
    }
    if (hasRefs(instance, "owner")) { return }
    if (hasRefs(instance, "tech_owner")) { return }
    def lastUser = getLastUserAction(instance.getBusinessId().toInteger(), '$process.action.create')
    if (lastUser) {
        String userId = getUserId(lastUser)
        if (!userId) return
        def userRefVal = new ReferenceValue(
            'User',
            userId.toInteger(),
            lastUser,
            new ReferenceValue.Relation('BUSINESS_OWNER', '')
        )
        instance.replaceAttr('owner', userRefVal)
        instance.replaceAttr('tech_owner', userRefVal)
    // def instForUpdate = instanceService.getInstance(instance.getBusinessId().toInteger()) // Получаем инстанс
    // instanceService.update(instance.getBusinessId().toInteger(), instForUpdate)           // Обновляем
    // LOG.info('\n\n\n\nUPDATED')
    }
}

/**********************************
 * Валидация полей
 **********************************/
void AttrValidation() {
    try {
        // def oldVersion = event.getParams().get('oldVersion')
        if (oldVersion != null) {
            //Словарь полей,
            // формат
            //def isNotEmptyFields = ['f1':['f11':['a','b','c'],  //  f1 не пустое, значения f11 списке  a,b,c
            //                         'newStatus': ['100','200'], // новый статуус в 100, 200
            //                         'oldStatus': ['10','50']  ],// старый статус в 10, 50
            //                   'f2':['f12':['d','b','c']],//  f2 не пустое, значения f12 списке  d,b,c
            //                   'f3':[:] // f3 не пустое
            //                  ]
            //
            //  проверяется поле f1 если пустое, проверяем содержимое ассоцированого значения
            //  если выполнены все условия, то генерируем ошибку. Если хотя бы одно условие не выполнено - считаем проверка пройдена
            // ключи newStatus, oldStatus - зарезервированы под значения статуса экземпляра
            //      "10": "Новый/На редактировании",
            //      "20": "В работе",
            //      "30": "На согласовании", //nash
            //      "40": "На проверке", "Контроль заполнения",
            //      "50": "На подтверждении", //nash
            //      "60": "Ожидает публикации",
            //      "70": "Опубликован",
            //      "90": "Новый/На редактировании",
            //      "100": "Новый/На редактировании",
            //      "110": "Контроль описания",
            //      "120": "На согласовании",
            //      "130": "На утверждении",
            //      "140": "Утверждён",
            //      "505": "Архитектурное ревью",
            //      "510": "Аналитическое ревью",
            //      "520": "Разработка", //nash
            //      "530": "Тестирование",
            //      "540": "Готово к выгрузке на прод"
            //
            def isNotEmptyFields = [
                'linkedBusinessRule':['condition': ['newStatus':['30', '520', '50', '70']],
                                'descr':'Бизнес-правила КД'
                            ],
                'dq_error_text':['condition': ['newStatus':['30', '520', '50', '70']],
                                'descr':'Описание ошибки'
                            ],
                'checking_source':['condition': ['newStatus':['50', '70']],
                                'descr':'Проверяемый источник данных'
                            ],
                'owner':['condition': ['newStatus':['30', '520', '50', '70']],
                                'descr':'Заказчик проверки'
                            ],
                'tech_owner':['condition': ['newStatus':['30', '520', '50', '70']],
                                'descr':'Разработчик проверки'
                            ],
                'BrdTeam1':['condition': ['newStatus':['520', '50', '70']],
                                'descr':'Команда заказчика проверки'
                            ],
                'BrdTeam2':['condition': ['newStatus':['520', '50', '70']],
                                'descr':'Команда разработчика'
                            ],
                'downstream_impl':['condition': ['newStatus':[ '50'], 'downstream_rec_id':['_ANY_']],
                                'descr':'Downstream-проверка'
                            ],
                'downstream_rec_id':['condition': ['newStatus':['50'], 'downstream_impl':['_ANY_']],
                                'descr':'Идентификатор записи Downstream-проверки'
                            ],
                'dq_type':['condition': ['newStatus':['50', '70']],
                                'descr':'Тип проверки']


            ]
            def checkSet = [:]
            Logger log = Logger.getLogger('')
            String oldStatus = oldVersion?.typedAttrByName(InstanceService.STATUS_FIELD)
            if (oldStatus.equals(newStatus)) { return }
            for (field in isNotEmptyFields.keySet())                       {
                       check = (instance.typedAttrByName(field)  == null || instance.typedAttrByName(field).isEmpty())
                       checkSet['0'] = [check]
                       if (check)
                       { 
                       extraConditions = isNotEmptyFields[field]?.condition
                       log.info("catch extra condition ${field}  ${extraConditions}")
                       if(extraConditions)
                       {
                       
                      for (entry in  extraConditions){
                       checkSet[entry.key] = []
                       log.info("BS->>${field}   ${entry.key}  ${entry.value} checkSet ${checkSet}")
                       switch(entry.key) { 
                           case 'newStatus':
                                log.info("BEFORE SAVE check NewStatus: ${field} >>> NewStauts :${newStatus}  checkSet ${checkSet} ")
                                checkSet[entry.key].add(entry.value.contains(newStatus))
                                break
                           case 'oldStatus': 
                                checkSet[entry.key].add(entry.value.contains(oldStatus))
                                 break
                           default:
                                    f = instance.typedAttrByName(entry.key)
                                    if (entry.value.contains("_ANY_") && (f!=null || !f?.isEmpty()) && !check) {
                                          checkSet[entry.key].add(true)
                                          log.info("BEFORE SAVE 1.check _ANY_>>> " +  entry.value) 
                                          break
                                         }
                                 
                                    if (entry.value.contains("_ANY_") && (f==null  || f?.isEmpty()) && check)
                                         {checkSet[entry.key].add(false)
                                         log.info("BEFORE SAVE 2.check _ANY_>>> ${entry.value} checkSet ${checkSet}") 
                                         break
                                         } 
                                    if (!entry.value.contains("_ANY_"))
                                           {
                                            log.info("BEFORE SAVE default not ANY >>> ${entry.value} : ${f} -->  ${entry.value.contains(f)}")
                                            if(entry.value.contains(f))
                                               {checkSet[entry.key].add(true)}
                                            else {checkSet[entry.key].add(false)}
                                            log.info("BEFORE SAVE check default: ${field}  value ${entry.value} checkSet ${checkSet} ")
                                            break  }
                                 }   // switch
                         }//each condition entry
                        } //if extraCondition
                                }
                           def checkResult = [] 
                           log.info("Before add to exception ${field} ${checkSet}")
                           if(checkSet.size()>1)   {checkSet.remove('0') }
                           checkSet.each{entry->
                                         checkResult.add(entry.value.every{it})}
                           log.info("ckeckRes exception ${checkResult}")  
                           if (checkResult.every{it}) 
                             {
                                var valError = new ValidationError() 
                                valError.setAttrName(field)
				valError.setAttributeLabel(isNotEmptyFields[field].descr)
				valError.addValidationDetail(isNotEmptyFields[field].descr, isNotEmptyFields[field].descr +" должно быть указано", ValidationErrorType.CRITICAL)
                                log.info("Validation object ${validationErrors}")
				validationErrors.add(valError)
                           }
                                checkSet = [:]
                    }
                }
        }   catch (Exception e) { throw new Exception(e.toString())
        StringWriter sw = new StringWriter()
        PrintWriter pw = new PrintWriter(sw)
        e.printStackTrace(pw)
        String sStackTrace = sw.toString() // stack trace as a string
        System.out.println(sStackTrace)
            }
                }

/** Если инициатор (owner) != разработчик (tech_owner), то поля Таблица БД (dbTable),
  * Атрибут таблицы БД (dbObjectLinkReq), Регламент запуска (runScheduleReq1) сделать необязательным
  */
void AlsoAttrValidation() {
    String oldStatus = oldVersion?.typedAttrByName(InstanceService.STATUS_FIELD)
    // if (oldStatus != '10') return
    if (!changeStatusCheck()) return
    if (getRefs(instance, 'owner')[0]?.id != getRefs(instance, 'tech_owner')[0]?.id ||
    !hasRefs(instance, 'owner') || !hasRefs(instance, 'tech_owner')) {
        // if (!hasRefs(instance, 'dbObjectLinkReq') && instance.attrByName('dq_type') != 'other') {
        //     sendValidationError(
        //         'Атрибут таблицы БД',
        //         'Атрибут таблицы БД',
        //         'Не заполнен Атрибут таблицы БД'
        //     )
        // }
        if (!hasRefs(instance, 'dbTable')) {
            sendValidationError(
                'Таблица БД',
                'Таблица БД',
                'Не заполнена Таблица БД'
            )
        }
        // if ((!hasRefs(instance, 'runScheduleReq1')) && !(instance.attrByName('UpdateRun'))) {
        //     sendValidationError(
        //         'Регламент запуска',
        //         'Регламент запуска',
        //         'Не заполнен Регламент запуска'
        //     )
        // }
    }
    // LOG.info("\n\nIF 0\n")
    if (newStatus in approvalStatuses) {
        // LOG.info("\n\nIF 1\n")
        //Бойцов А.Г. - DGO-1575 
        
        
        if (!(instance.attrByName('UpdateRun'))) {
            if ( !(instance.attrByName('dq_run_type') == 'paused')) {
            // LOG.info("\n\nIF 2\n")
            if (!(instance.attrByName('dq_period_cron'))){
                // LOG.info("\n\nIF 3\n")
                sendValidationError(
                    'Расписание запуска проверки',
                    'Расписание запуска проверки',
                    'Не заполнено расписание запуска проверки'
                )
            }
            }
        }
    }
}

///// 04/09/2026 Бойцов А.Г. - ФАстрек проверок
def check_hist(instance) {
         String sql_stmt = """select status, bad_rec_count 
        from dq.dqimplementation_stat ds
        where ds.dqimplementation_code =?
        and run_ts > current_date - interval '1 day'
        order by ds.run_ts desc limit 1"""


        dag_name = instance.attrByName('emmDagName')
        resSql = jdbcTemplate.queryForList(sql_stmt, dag_name)
        run_status=resSql[0]?.status
        bad_rec_count = resSql[0]?.bad_rec_count

        if (run_status == 'success' && bad_rec_count < 100000) {
            return true
        }
        return false
    }
//
def check_standard(instance) {
    return instance.attrByName('standardLogic')
}
//
def check_name(instance) {
    name = instance.name
    table = instance.attrByName('dbTable')
    t_instance = instanceService.getInstance(table?.id)
    schema = t_instance?.parent?.name
    t_name_part = name.split(':')[0]
    n_list = t_name_part.split('\\.')
    n_sch = n_list[0]
    n_tbl = n_list[1]
    return n_sch == schema && n_tbl == t_instance?.name
}

def check_time(instance) {
    sql_stmt = """select 
    case 
        when (end_ts - run_ts) <= interval '5 minutes' then true
        else false
        end as check_result
    from dq.dqimplementation_stat ds
    where ds.dqimplementation_code = ?
    and run_ts > current_date - interval '1 day'
    order by ds.run_ts desc 
    limit 1;"""
    dag_name = instance.attrByName('emmDagName')
    resSql = jdbcTemplate.queryForList(sql_stmt, dag_name)
    run_status=resSql[0]?.check_result
    if (run_status != null) {
        return run_status
    }
    return false
}

//
def check_fsd(instance) {
    name = instance.name
    table = instance.attrByName('dbTable')
    dq_pk = instance.attrByName('dbObjectLinkPKcol')

    t_instance = instanceService.getInstance(table?.id)

    if (t_instance.attrByName('FSD')) {
        fsd_tbl = instanceService.getInstance(t_instance.attrByName('FSD')?.id)?.attrByName('tableAttr')
        pks = fsd_tbl.findAll { it.Is_pk_field == true }

        
        def dqPkNames = dq_pk.collect { it.name }
        def fsdPkNames = pks.collect { it.DM_col_name }

        // Проверяем, что все имена из dq_pk присутствуют в pks
        def missingInDq = fsdPkNames - dqPkNames
        def allFsdPkPresent = missingInDq.isEmpty()

        // def missing = dqPkNames - fsdPkNames
        // def allMatch = missing.isEmpty()
        return allFsdPkPresent
        // return [
        //     match: allFsdPkPresent,
        //     dqPkNames: dqPkNames,
        //     fsdPkNames: fsdPkNames,
        //     missingInDq: missingInDq,
        //     extraInDq: dqPkNames - fsdPkNames,
        //     message: allFsdPkPresent ? "✅ Все PK поля из FSD присутствуют в Идентификаторе записи" : "❌ В Идентификаторе записи отсутствуют поля FSD PK"
        // ]
        
        // return [allMatch: allMatch, missing: missing, dqPkNames: dqPkNames, fsdPkNames: fsdPkNames]
    } else { return true }
}

def check_ft(instance) {
    //
    fsd = check_fsd(instance)
    hist = check_hist(instance)
    time = check_time(instance)
    name = check_name(instance)
    st = check_standard(instance)


  
  check_list = [fsd, hist, time, name, st]
  // return check_list
//   LOG.info(" Результат ФТ ${check_list}")
  all_ok = check_list.every() == true
  return all_ok
    
}

/**********************************
 * MAIN FLOW
 **********************************/
/**
 * Главная точка входа сценария: настраивает стандартную/нестандартную логику,
 * обрабатывает источники, генерирует SQL (full_select_script/full_bad_records_script),
 * выполняет пост-валидацию при смене статуса, автозаполнение и линковку глоссария.
 *
 * Внутри использует хелперы: {@code setSourceLinks}, {@code setCheckedDbObjectByTemplate},
 * {@code validateJoinCondition}, SQL-хелперы для колонок и др.
 *
 * Побочные эффекты: множественные {@code replaceAttr}, возможные {@code sendValidationError},
 * модификация связанных сущностей (LinkGloss).
 */
def runMain(instance) {
    // 0) Инициализация standardLogic
    if (oldVersion == null) {
        // instance.replaceAttr('standardLogic', true)
        instance.replaceAttr('shouldClose', true)
    }

    // implSamplUsage(instance)

    // 1) Владелец/коды/метод создания
    // copyOwnerAttrsFromReq(instance) // Legacy
    ensureCodes(instance)
    // AutocomleteBool()
    Boolean crFlg = ensureCreationMethod(instance)

    // 2) Тип проверки
    def isStandard = instance.attrByName('standardLogic')
    def dq_type    = instance.attrByName('dq_type')

    if (dq_type == 'check_status') {
        instance.replaceAttr('standardLogic', false)
        isStandard = false
        instance.replaceAttr('boundCondition1', 50)
        instance.replaceAttr('boundCondition2', 100)
        clearUnoTableChecks()
    }
    else if (dq_type == 'other') {
        // LOG.info('\n\n\nIM HERE 1\n\n\n')
        instance.replaceAttr('standardLogic', false)
        instance.replaceAttr('intervalStartStr', '')
        instance.replaceAttr('intervalFinishStr', '')
        instance.replaceAttr('dq_type_compare', null)
        isStandard = false
    // clearUnoTableChecks()
    }
    else if (dq_type in ['notnull', 'unique', 'unique_pk', 'template']) {
        // LOG.info('\n\n\nIM HERE 2\n\n\n')
        instance.replaceAttr('intervalStartStr', '')
        // LOG.info('\n\n\nREPLACED\n\n\n')
        instance.replaceAttr('intervalFinishStr', '')
        instance.replaceAttr('dq_type_compare', null)
        clearUnoTableChecks()
    }
    else if (dq_type in ['interval', 'length', 'count']) {
        // LOG.info('\n\n\nIM HERE 3\n\n\n')
        instance.replaceAttr('dq_type_compare', null)
        clearUnoTableChecks()
    }
    else if (dq_type in ['gt', 'gteq']) {
        // LOG.info('\n\n\nIM HERE 4\n\n\n')
        instance.replaceAttr('intervalFinishStr', '')
        instance.replaceAttr('dq_type_compare', null)
        clearUnoTableChecks()
    }
    else if (dq_type in ['lt', 'lteq']) {
        // LOG.info('\n\n\nIM HERE 5\n\n\n')
        instance.replaceAttr('intervalStartStr', '')
        instance.replaceAttr('dq_type_compare', null)
        clearUnoTableChecks()
    }
    else if (dq_type in ['two_fields_compare', 'count_compare']) {
        // LOG.info('\n\n\nIM HERE 6\n\n\n')
        instance.replaceAttr('intervalStartStr', '')
        instance.replaceAttr('intervalFinishStr', '')
    }

    // 3) Источники и базовый FROM
    int flg_setSourceLinks = 1
    String tmpTable1 = ''
    String tmpTable2 = ''
    String fromClause = ''
    String src1Type = ''
    String src2Type = ''

    if (hasRefs(instance, 'checking_source')) {
        for (ref in getRefs(instance, 'checking_source')) {
            def srcObj = instanceService.getInstance(ref.id())
            src1Type   = srcObj.getMetaClassName()

            if (src1Type == 'DQDBSource') {
                def t = srcObj.attrByName('dict_source_type')
                if (['api', 'kafka', 'file'].contains(t)) {
                    instance.replaceAttr('standardLogic', false)
                    instance.replaceAttr('dq_type', 'other')
                    dq_type = 'other'; isStandard = false; flg_setSourceLinks = 0
                }
            }
            tmpTable1   = resolveTmpTableName(srcObj)
            fromClause  = "FROM ${tmpTable1} "
        }
    }
    // 4) Основная генерация (стандартная логика + есть oldVersion)
    if (isStandard && oldVersion != null) {
        int needGenSelect   = 1
        int needAddDistinct = 0
        String selectAllSql = ''
        String selectBadSql = ''

        if (hasRefs(instance, 'check_template')) {
            instance.replaceAttr('dq_type', 'template')
            dq_type = 'template'
        }

        if (hasRefs(instance, 'checking_source')) {
            setSourceLinks(instance, 'checking_source', 'checking_table', 'dbObjectLinkPKcol')
            if (dq_type != 'other') {
                def checking_source  = getRefs(instance, 'checking_source')
                Long src1Id = checking_source ? checking_source[-1].id() : -1L
                Long src2Id = -1L

                if (dq_type in ['two_fields_compare', 'count_compare']) {
                    def dq_type_compare = instance.attrByName('dq_type_compare')
                    if (!hasValue(dq_type_compare)) {
                        sendValidationError('dq_type_compare','Не заполнен Тип сравнения','Укажите Тип сравнения')
                    } else {
                        setSourceLinks(instance, 'checking_source2', 'checking_table2', 'dbObjectLinkPKcol2')
                        if (hasRefs(instance, 'checking_source2')) {
                            for (ref in getRefs(instance, 'checking_source2')) {
                                def srcObj2 = instanceService.getInstance(ref.id())
                                src2Type = srcObj2.getMetaClassName()
                                tmpTable2 = resolveTmpTableName(srcObj2)
                                src2Id = ref.id()
                            }
                        } /// не хватало

                            // if (((src1Type != 'DQDBSource') || (src2Type != 'DQDBSource')) && (newStatus in approvalStatuses)) {
                            //     sendValidationError(
                            //         'checking_source',
                            //         'Неправильно выбраны источники или тип проверки',
                            //         'Сравнение возможно только для источников-наборов данных (DQDBSource).'
                            //     )
                            //     if (dq_type == 'count_compare') needGenSelect = 0
                            // }
                            // else 
                            if (dq_type == 'two_fields_compare') {
                                if (src1Id != src2Id) {
                                    def sjText = attrText(instance, 'source_join_condition')
                                    validateJoinCondition(instance, sjText)
                                    if (hasValue(sjText)) fromClause = sjText
                                }
                                if (hasRefs(instance, 'dbObjectLink')) {
                                    if (hasRefs(instance, 'dbObjectLink2')) {
                                        def c1 = instance.attrByName('dbObjectLink')[0].name()
                                        def c2 = instance.attrByName('dbObjectLink2')[0].name()
                                        selectAllSql = "SELECT * ${fromClause} "
                                        selectBadSql = fromClause +
                                            "\n WHERE NOT (${tmpTable1}.${c1} ${getCompareTypeSign(dq_type_compare)} ${tmpTable2}.${c2})"
                                    } else {
                                        sendValidationError('dbObjectLink2','Не заполнен второй Проверяемый объект данных','Укажите второй объект')
                                    }
                                } else if (newStatus in approvalStatuses) {
                                    sendValidationError('dbObjectLink','Не заполнен Проверяемый объект данных','Укажите объект')
                                }
                            }
                            else if (dq_type == 'count_compare') {
                                needGenSelect = 0
                                def base =
                                    'with S1 as (select count(1) c1, 1 as id from @S1),\n' +
                                    'S2 as (select count(1) c2, 1 as id from @S2)\n' +
                                    'select c1, c2 from S1 join S2 on s1.id = s2.id\n' +
                                    "where not (c1 ${getCompareTypeSign(dq_type_compare)} c2)"
                                selectBadSql = "SELECT c1 as count1, c2 as count2 \nFROM (${base}) as c"
                                selectAllSql = 'SELECT 1'
                                instance.replaceAttr('boundCondition1', 50)
                                instance.replaceAttr('boundCondition2', 100)
                            //} лишнее
                        } else {
                            sendValidationError('checking_source2','Не заполнен Источник 2','Укажите второй источник')
                        }
                    }
                }
                else if (hasValue(fromClause)) { // одноисточниковые проверки
                    selectAllSql = "SELECT * ${fromClause} "
                    if (hasRefs(instance, 'dbObjectLink')) {
                        def colName = instance.attrByName('dbObjectLink')[0].name()
                        switch (dq_type) {
                            case 'notnull':
                                selectBadSql = fromClause + '\n WHERE ' + getNotNullCheckColumns(instance, 'dbObjectLink', tmpTable1)
                                break
                            case 'interval':
                                def i1 = instance.attrByName('intervalStartStr')
                                def i2 = instance.attrByName('intervalFinishStr')
                                if (!hasValue(i1) || !hasValue(i2)) {
                                    sendValidationError('intervalStartStr','Не заполнены границы диапазона','Укажите обе границы')
                                } else {
                                    selectBadSql = fromClause + "\n WHERE ${colName} NOT BETWEEN ${i1} AND ${i2}"
                                }
                                break
                            case 'gt': case 'lt': case 'gteq': case 'lteq':
                                def boundField = (dq_type in ['gt', 'gteq']) ? 'intervalStartStr' : 'intervalFinishStr'
                                def bound      = instance.attrByName(boundField)
                                if (!hasValue(bound)) {
                                    sendValidationError(boundField, 'Не заполнена граница диапазона', 'Укажите значение')
                                } else {
                                    def op = ['gt':'>', 'lt':'<', 'gteq':'>=', 'lteq':'<='][dq_type]
                                    selectBadSql = fromClause + "\n WHERE NOT (${colName} ${op} ${bound})"
                                }
                                break
                            case 'length':
                            case 'count':
                                def i1 = instance.attrByName('intervalStartStr')
                                def i2 = instance.attrByName('intervalFinishStr')
                                if (!hasValue(i1) || !hasValue(i2)) {
                                    sendValidationError('intervalStartStr','Не заполнены границы диапазона','Укажите значения')
                                } else if (!i1.isInteger() || !i2.isInteger()) {
                                    sendValidationError('intervalStartStr','Границы диапазона должны быть числовыми','Укажите числа')
                                } else if (dq_type == 'length') {
                                    selectBadSql = fromClause + "\n WHERE LENGTH(TRIM(${colName}::text)) NOT BETWEEN ${i1} AND ${i2}"
                                } else { // count
                                    needGenSelect = 0
                                    def base = "SELECT COUNT(*) as cnt ${fromClause}"
                                    selectBadSql = "SELECT cnt FROM (${base}) as c WHERE c.cnt NOT BETWEEN ${i1} AND ${i2}"
                                    selectAllSql = 'SELECT 1'
                                    instance.replaceAttr('boundCondition1', 50)
                                    instance.replaceAttr('boundCondition2', 100)
                                }
                                break
                            case 'unique':
                                needGenSelect = 0
                                if (CheckPKColumns(instance, newStatus) == 1) {
                                    // колонки, по которым проверяем уникальность (без алиасов)
                                    def uniqueColsSelect = getColumnsNoAlias(instance, 'dbObjectLink', tmpTable1)
                                    def groupByCols      = uniqueColsSelect

                                    def pkNames = getRefs(instance, 'dbObjectLinkPKcol').collect { it.name() }

                                    // Значения Идентификатора записи агрегируются через string_agg.
                                    def pkExpr    = pkNames.collect { "${tmpTable1}.${it}::text" }.join(" || '|' || ")
                                    def pkAlias   = pkNames[0]
                                    // Комментарий с составом string_agg нужен только для нескольких атрибутов.
                                    def pkComment = pkNames.size() > 1 ? "\n -- Идентификатор записи: ${pkNames.join('|')}\n" : ' '
                                    selectBadSql =
                                        " SELECT ${uniqueColsSelect}, COUNT(*) AS cnt, " +
                                        "string_agg(${pkExpr}, ',') AS ${pkAlias}" +
                                        pkComment +
                                        fromClause +
                                        " group by ${groupByCols} having COUNT(*) > 1"
                                }
                                break
                            case 'unique_pk':
                                needGenSelect = 0
                                if (CheckPKColumns(instance, newStatus) == 1) {
                                    // Проверяемые атрибуты совпадают с Идентификатором записи.
                                    // string_agg не используется, атрибуты выводятся в первоначальном виде.
                                    def pkColsSelect = getColumnsNoAlias(instance, 'dbObjectLink', tmpTable1)
                                    selectBadSql =
                                        " SELECT ${pkColsSelect}, COUNT(*) AS cnt " +
                                        fromClause +
                                        " group by ${pkColsSelect} having COUNT(*) > 1"
                                }
                                break
                            case 'relevance':
                                needGenSelect = 0

                                selectAllSql = 'SELECT 1'

                                selectBadSql =
                                    "select \n" +
                                    "  0 as cnt,\n" +
                                    "  current_date::date as check_dttm\n" +
                                    "where not exists (\n" +
                                    "  select 1\n" +
                                    "  ${fromClause}\n" +
                                    "  where dm_load_ts::date = current_date::date\n" +
                                    ")"

                                break
                            case 'template':
                                if (hasRefs(instance, 'check_template')) {
                                    for (ref in getRefs(instance, 'check_template')) {
                                        if (ref.type == 'DQImplSample') {
                                            def tmplObj = instanceService.getInstance(ref.id())
                                            def script  = tmplObj.attrByName('filter_condition2')?.text
                                            def params  = instance.attrByName('templateParams')
                                            def tmplInp = tmplObj.attrByName('dq_parameter_code2')
                                            if (params && params.size() > 0) {
                                                for (p in params) script = script.replace(p.templateParamKey, p.templateParamValue)
                                                setCheckedDbObjectByTemplate(instance, 'checking_source', 'checking_table', 'dbObjectLink', params)
                                            } else {
                                                int i = 0
                                                for (p in tmplInp) {
                                                    def autoCol = instance.attrByName('dbObjectLink')[i] ? instance.attrByName('dbObjectLink')[i].name : ''
                                                    def p1 = [templateParamKey: p.inputParamCode, templateParamValue: autoCol]
                                                    if (params == null) params = []
                                                    params.add(p1)
                                                    script = script.replace(p1.templateParamKey, p1.templateParamValue)
                                                    i++
                                                }
                                                instance.replaceAttr('templateParams', params)
                                            }
                                            selectBadSql = fromClause + '\n WHERE (' + script + ')'
                                        } else {
                                            def tmplObj = instanceService.getInstance(ref.id())
                                            def script  = tmplObj.attrByName('template_script')?.text
                                            def params  = instance.attrByName('templateParams')
                                            def tmplInp = tmplObj.attrByName('inputParams')
                                            if (params && params.size() > 0) {
                                                for (p in params) script = script.replace(p.templateParamKey, p.templateParamValue)
                                                setCheckedDbObjectByTemplate(instance, 'checking_source', 'checking_table', 'dbObjectLink', params)
                                            } else {
                                                int i = 0
                                                for (p in tmplInp) {
                                                    def autoCol = instance.attrByName('dbObjectLink')[i] ? instance.attrByName('dbObjectLink')[i].name : ''
                                                    def p1 = [templateParamKey: p.inputParamCode, templateParamValue: autoCol]
                                                    if (params == null) params = []
                                                    params.add(p1)
                                                    script = script.replace(p1.templateParamKey, p1.templateParamValue)
                                                    i++
                                                }
                                                instance.replaceAttr('templateParams', params)
                                            }
                                            selectBadSql = fromClause + '\n WHERE (' + script + ')'
                                        }
                                    }
                                } else {
                                    sendValidationError('check_template','Не указан шаблон проверки','Укажите шаблон и соответствие параметров')
                                }
                                break
                            default:
                                selectBadSql = fromClause
                        }
                    } else if (dq_type == 'relevance') {
                        needGenSelect = 0
                        
                        selectAllSql = 'SELECT 1'

                        selectBadSql =
                            "select \n" +
                            "  0 as cnt,\n" +
                            "  current_date::date as check_dttm\n" +
                            "where not exists (\n" +
                            "  select 1\n" +
                            "  ${fromClause}\n" +
                            "  where dm_load_ts::date = current_date::date\n" +
                            ")"
                    } else if (dq_type != 'count' && (newStatus in approvalStatuses) && dq_type != 'other') {
                        sendValidationError('dbObjectLink','Не заполнен Проверяемый объект данных','Укажите объект')
                    }
                }

                // Добавить SELECT-шапку (для «строчных» проверок)
                if (dq_type == 'count') { /* уже собран выше */ }
                if (needGenSelect == 1 && CheckPKColumns(instance, newStatus) == 1) {
                    def pk      = getCommaSeparatedColumns(instance, 'dbObjectLinkPKcol', tmpTable1)
                    def dataCol = getCommaSeparatedColumns(instance, 'dbObjectLink',     tmpTable1)
                    def selectHead = (needAddDistinct == 1) ? ' SELECT DISTINCT ' : ' SELECT '
                    selectBadSql = "${selectHead}${pk}, ${dataCol} ${selectBadSql ?: fromClause}"
                }

                // Сохранить оба скрипта (+замены @S1/@S2)
                instance.replaceAttr('full_select_script',
                    new LanguageType((selectAllSql ?: "SELECT * ${fromClause}").replace('@S1', tmpTable1).replace('@S2', tmpTable2), 'sql'))
                if (crFlg){
                instance.replaceAttr('full_bad_records_script',
                    new LanguageType((selectBadSql ?: fromClause).replace('@S1', tmpTable1).replace('@S2', tmpTable2), 'sql'))
                }
            }
        } else if (newStatus in approvalStatuses) {
            sendValidationError('checking_source','Не заполнен Источник данных','Укажите Источник данных')
        }
    }
    // 5) Нестандартная логика или нет oldVersion
    else {
        def bad = instance.attrByName('full_bad_records_script')
        if (hasValue(bad) && crFlg) {
            instance.replaceAttr('full_bad_records_script', new LanguageType(bad.text.replace('STATUS', 'status'), 'sql'))
        }
        if (flg_setSourceLinks == 1) {
            setSourceLinks(instance, 'checking_source',  'checking_table',  'dbObjectLinkPKcol')
            setSourceLinks(instance, 'checking_source2', 'checking_table2', 'dbObjectLinkPKcol2')
        }
    }
    // 6) Пост-проверка при смене статуса и валидация полей
    if (oldVersion && oldVersion.attrByName('status') != instance.attrByName('status')) {
        def bad = instance.attrByName('full_bad_records_script')
        if ((bad == null || bad.text == '') && (newStatus in approvalStatuses)) {
            sendValidationError('full_bad_records_script','Не заполнен Скрипт отбора данных','Заполните скрипт отбора данных')
        }
    }
    LOG.info("\n\nStart validtion\n")
    AttrValidation()
    AlsoAttrValidation()
    LOG.info("\n\nEnd validtion\n")
    // 7) Автозаполнение и линковка
    // if (!instance.attrByName('standardLogic') && oldVersion != null) {
    //     def t  = instance.attrByName('full_select_script')
    //     def t2 = instance.attrByName('full_bad_records_script')
    //     def select = 'SELECT * FROM ' + (hasValue(fromClause) ? tmpTable1 : 'table_name')
    //     def selectwhere = select + ' WHERE . = .'
    //     if (t == null || !hasValue(t.text))  instance.replaceAttr('full_select_script',       new LanguageType(select, 'sql'))
    //     if ((t2 == null || !hasValue(t2.text)) && crFlg) instance.replaceAttr('full_bad_records_script', new LanguageType(selectwhere, 'sql'))
    // }

    // LinkGloss(instance)
    if (oldVersion?.typedAttrByName(InstanceService.STATUS_FIELD) == '10') {
        Autocomplete10()
    }
    PutCreatorToOwner(instance)
    AutocompleteUpdateRun()
    Autocomplete50(instance)
    AutocompleteDq_error_text()
    if (newStatus in approvalStatuses) {
        copyScriptToFilter(instance)
    }

    if (dq_type == 'relevance') {
        // LOG.info('relevance')

        String rawValue = ''

        def bdTblRef = instance.attrByName('dbTable')?.with { it ? it[0] : null }
        if (bdTblRef?.id) {
            def bdTblInst = instanceService.getInstance(bdTblRef.id)
            def fsdRef = bdTblInst?.attrByName('FSD')?.with { it ? it[0] : null }

            if (fsdRef?.id) {
                def fsdInst = instanceService.getInstance(fsdRef.id)
                def dataTimeObj = fsdInst?.attrByName('DataTime')

                if (dataTimeObj instanceof Collection) {
                    dataTimeObj = dataTimeObj ? dataTimeObj[0] : null
                }

                if (dataTimeObj instanceof String) {
                    rawValue = dataTimeObj.trim()
                } else if (dataTimeObj != null) {
                    try {
                        rawValue = dataTimeObj.value?.toString()?.trim()
                    } catch (ignored) {
                        rawValue = dataTimeObj.toString()?.trim()
                    }
                }
            }
        }

        String cronHour = '08'
        if (rawValue?.isInteger()) {
            Integer hourValue = rawValue.toInteger()
            if (hourValue >= 0 && hourValue <= 23) {
                cronHour = String.format('%02d', hourValue)
            }
        }

        String cronValue = "00 ${cronHour} * * *"

        def dqCronObj = instance.attrByName('dq_period_cron')
        if (dqCronObj instanceof Collection) {
            dqCronObj = dqCronObj ? dqCronObj[0] : null
        }

        String currentCronValue = ''
        if (dqCronObj instanceof String) {
            currentCronValue = dqCronObj.trim()
        } else if (dqCronObj != null) {
            try {
                currentCronValue = dqCronObj.value?.toString()?.trim()
            } catch (ignored) {
                currentCronValue = dqCronObj.toString()?.trim()
            }
        }

        LOG.info("relevance: current dq_period_cron='${currentCronValue}', new cronValue='${cronValue}'")

        if (!currentCronValue) {
            instance.replaceAttr('dq_period_cron', cronValue)
            LOG.info("relevance: dq_period_cron set to '${cronValue}'")
        } else {
            LOG.info("relevance: dq_period_cron already exists, skip update")
        }
    }

    // Сотников А.А. не публикуем если последний статус не success + Тупицкий Д.С.
    if (oldVersion != null) {
        if (oldVersion.attrByName('full_bad_records_script') != instance.attrByName('full_bad_records_script') ||
        oldVersion.attrByName('full_select_script') != instance.attrByName('full_select_script')) {
            def rn = new Date()
            def sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSX")
            instance.replaceAttr('dateValidation', sdf.format(rn))
        }
    }
    newStatus  = getNewStatus()
    LOG.info("[dag stat] newStatus ${newStatus}")
    if(newStatus =='50' && instance.attrByName("Creation_method") != "Автоматическая генерация"){
        String sql_stmt = "select status, end_ts from dq.dqimplementation_stat ds where ds.dqimplementation_code =? order by ds.run_ts desc limit 1"   
        dag_name = instance.attrByName('emmDagName')
        resSql = jdbcTemplate.queryForList(sql_stmt, dag_name)
        run_status=resSql[0]?.status
        LOG.info("[dag stat] run_status ${run_status}")
        def dv = instance.attrByName('dateValidation')

        if(run_status==null){
            sendValidationError('emmDagName','Не было запусков проверки!','Не было запусков проверки!')
        }
        else if (run_status!='success'){
            sendValidationError('emmDagName','Последний запуск проверки не успешный!','Последний запуск проверки не успешный!')
        }
        if (dv && resSql?.end_ts[0] && resSql.end_ts[0] < new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSX").parse(dv.toString())) {
            sendValidationError('emmDagName','Последняя версия скрипта не запускалась!','Последняя версия скрипта не запускалась!')
        }
    }

    // Бойцов А.Г. - DGO-1395 - проверка перехода на контроль описания и заполнения ПК
   /*  if (newStatus == '40' || newStatus =='50') {
        fsd_check = check_fsd(instance)
        if (!fsd_check) {
            sendValidationError('dbObjectLinkPKcol','Не верно указан Идентификатор записи','Не верно указан Идентификатор записи')
        }



    } */

    //Бойцов А.Г. DGO-1575 - отключение запуска по расписанию и приостановка проверок

    dq_run_type = instance.attrByName('dq_run_type')
    if (dq_run_type == 'paused') {
        instance.replaceAttr('dq_period_cron','')
    } else if (dq_run_type == 'after_update') {
        instance.replaceAttr('dq_period_cron','')
        instance.replaceAttr('UpdateRun', true)
    } 
    
    //Бойцов А.Г. - DGO-1488 Запрет проверок view на greenplum


    is_new = instance.version.tokenize('.')[0].toInteger() < 1
    try {

        table = instance.attrByName('checking_source')
        table_instance = instanceService.getInstance(table.id)
        schema = instanceService.getInstance(table_instance.parent.id)
        db = instanceService.getInstance(schema.parent.id)
        server = instanceService.getInstance(db.parent.id)
        is_greenplum = server.name == 'GREENPLUM'

        if (is_new && is_greenplum) {
        table_type = table_instance.attrByName('dboType')
        if (table_type == 'view') {
            error_text = "Проверка для представлений запрещена, в связи с большой нагрузкой на GreenPlum"
            sendValidationError('checking_source','Проверка VIEW на GREENPLUM', error_text)
        }
        
        } 
    } catch (e) {

    }
}

// Запуск
runMain(instance)

