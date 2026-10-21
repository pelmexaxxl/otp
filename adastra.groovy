import datasapience.gbapiextension.core.GlossaryMode
import datasapience.gbapiextension.core.exception.ValidationError
import datasapience.gbapiextension.core.exception.ValidationErrorType
import datasapience.gbapiextension.core.model.ClassInstance
import datasapience.gbapiextension.core.model.LanguageType
import datasapience.gbapiextension.core.model.ReferenceValue
import datasapience.gbapiextension.search.elasticsearch.SearchRequest

import java.util.logging.Logger

class SQLScript {
    public String text
    public String language
}


def sendValidationError(String title, String label, String content) {
    def validationError = new ValidationError()
    validationError.setAttrName(title)
    validationError.setAttributeLabel(label)
    validationError.addValidationDetail(title, content, ValidationErrorType.CRITICAL)
    validationErrors.add(validationError)
}

def setSourceLinks(def instance, String checking_source_field, String checking_table_field, String dbObjectLinkPKcol_field) {
    def checking_source = instance.attrByName(checking_source_field)
    if (checking_source != null) {
        for (checking_src in checking_source) {
            def checking_source_obj = instanceService.getInstance(checking_src.id())
            ClassInstance tbl_inst = null
            if (checking_source_obj.getMetaClassName() == "DQDBSource") {
                def src_dbObjectLink = checking_source_obj.attrByName("dbObjectLink")
                instance.replaceAttr(checking_table_field, src_dbObjectLink)
                if ((src_dbObjectLink != null) && (src_dbObjectLink != '') && (src_dbObjectLink[0] != null)) {
                    tbl_inst = instanceService.getInstance(src_dbObjectLink[0].id())
                }
            } else {
                tbl_inst = checking_source_obj
                instance.replaceAttr(checking_table_field, checking_source)
            }
            if (tbl_inst != null) {
                List<ClassInstance> columns = instanceService.getInstancesByParent(tbl_inst.getBusinessId(), 1000, 0, "name", "asc", GlossaryMode.WORKPLACE)
                def pk_cols = instance.attrByName(dbObjectLinkPKcol_field)
                if (pk_cols == null || pk_cols == "" || pk_cols == "[]" || pk_cols.size() == 0) {
                    int i = 0
                    pk_cols = null
                    for (def col : columns) {
                        if (col.attrByName('memberPK')) {
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
}

int setCheckedDbObjectByTemplate(def instance, String checking_source_field, String checking_table_field, String dbObjectLink_field, def params) {
    def checking_source = instance.attrByName(checking_source_field)
    def src_cnt = 0
    def res = 1
    if (checking_source != null) {
        for (checking_src in checking_source) {
            def checking_source_obj = instanceService.getInstance(checking_src.id())
            src_cnt = src_cnt + 1
            def src_dbObjectLink = checking_source_obj.attrByName("dbObjectLink")
            if ((src_dbObjectLink != null) && (src_dbObjectLink != '') && (src_dbObjectLink[0] != null)) {
                instance.replaceAttr(checking_table_field, src_dbObjectLink)
                ClassInstance tbl_inst = instanceService.getInstance(src_dbObjectLink[0].id())
                List<ClassInstance> columns = instanceService.getInstancesByParent(src_dbObjectLink[0].id(), 1000, 0, "name", "asc", GlossaryMode.WORKPLACE)
                def CheckedDbObject = instance.attrByName(dbObjectLink_field)
                CheckedDbObject = null
                for (p in params) {
                    def found = 0
                    for (def col : columns) {
                        if (col.getName().toUpperCase() == p.templateParamValue.toUpperCase()) {
                            found = 1
                            if (CheckedDbObject == null) {
                                instance.setReference(dbObjectLink_field, col, '')
                                CheckedDbObject = instance.attrByName(dbObjectLink_field)
                            } else {
                                CheckedDbObject.add(new ReferenceValue('TableColumn', col.getBusinessId(), col.getName(), new ReferenceValue.Relation('CHECK', '')))
                                CheckedDbObject = CheckedDbObject.unique()
                            }
                        }
                    }
                    if (found == 0) {
                        sendValidationError("templateParamValue", "Колонка " + p.templateParamValue + ", указанная в параметрах, отсутствует в проверяемом источнике данных", "Укажите корректное имя колонки")
                        res = 0
                    }
                }
            }
        }
    }
    return res
}

String getCompareTypeSign(String dq_type_compare) {
    if (dq_type_compare == "gt") return ">"
    if (dq_type_compare == "gteq") return ">="
    if (dq_type_compare == "lt") return "<"
    if (dq_type_compare == "lteq") return "<="
    if (dq_type_compare == "equal") return "="
    if (dq_type_compare == "not_equal") return "!="
}

String getCommaSeparatedColumns(def instance, String colRefFieldName, String tableName) {
    def res = ""
    def col_list = instance.attrByName(colRefFieldName)
    for (col in col_list) {
        def col_obj = instanceService.getInstance(col.id())
        if (res == "") {
            res = tableName + "." + col_obj.name
        } else {
            res = res + ", " + tableName + "." + col_obj.name
        }
    }
    return res
}

String getConcatColumns(def instance, String colRefFieldName, String tableName) {
    def res = ""
    def col_list = instance.attrByName(colRefFieldName)
    for (col in col_list) {
        def col_obj = instanceService.getInstance(col.id())
        if (res == "") {
            res = tableName + "." + col_obj.name + "::text"
        } else {
            res = res + " || '|' || " + tableName + "." + col_obj.name + "::text"
        }
    }
    return res
}

String getCommaSeparatedColumnsJSON(def instance, String colRefFieldName, String tableName) {
    def res = ""
    def col_list = instance.attrByName(colRefFieldName)
    for (col in col_list) {
        def col_obj = instanceService.getInstance(col.id())
        def s = "'" + col_obj.name + "', " + tableName + "." + col_obj.name
        if (res == "") {
            res = s
        } else {
            res = res + ", " + s
        }
    }
    return res
}

def getParentObj(e) {
    return instanceService.getInstance(e.getParent().id)
}

String checkForTableIsFromTrino(checking_source_obj) {
    dbs_id = getParentObj(getParentObj(getParentObj(checking_source_obj))).getBusinessId()
    db_id = getParentObj(getParentObj(checking_source_obj)).getBusinessId()
    def conn_attributes = new HashMap<>()
    conn_attributes.put("targetRootObject", dbs_id)
    def req = SearchRequest.builder()
            .objectTypes(List.of("TrinoConnection"))
            .searchAdvanced(conn_attributes)
            .limit(100)
            .offset(0)
            .glossaryMode(GlossaryMode.PUBLIC)
            .build();
    def results = searchService.searchObjects(req);
    if (results.size() > 0) {
        //trinoConn = instanceService.getInstance(results[0].objectId).getName()
        return instanceService.getInstance(db_id).getName()
    } else {
        return null
    }
}


int CheckPKColumns(def instance) {
    int res
    if (instance.attrByName('dbObjectLinkPKcol') != null && instance.attrByName('dbObjectLinkPKcol')[0] != null) {
        res = 1
    } else {
        if (dq_type !in ['length', 'count', 'count_compare', 'check_status']) {
            sendValidationError("dbObjectLinkPKcol", "Не указан Идентификатор записи", "Необходимо указать поле (поля), составляющие Идентификатор записи (Первичный ключ) для источника данных. Он используется для фиксации в журнал информации об ошибочной записи.")
        }
        res = 0
    }
    return res
}


// Начало

Logger log = Logger.getLogger('')
def oldVersion = event.getParams().get("oldVersion")
if (oldVersion == null) {
    instance.replaceAttr('standardLogic', true)
}
def isStandard = instance.attrByName('standardLogic')
def dq_type = instance.attrByName('dq_type')
if (dq_type == 'check_status') { // Тип проверки "Проверка регламента"
    instance.replaceAttr('standardLogic', false)
    isStandard = false
    instance.replaceAttr('boundCondition1', 50)
    instance.replaceAttr('boundCondition2', 100)
}
if (dq_type == 'other') { // тип проверки - Общее
    instance.replaceAttr('standardLogic', false)
    isStandard = false
}
def flg_setSourceLinks = 1
def tmp_table_name = ""
def trinoDbName
if (instance.attrByName('checking_source') != null && instance.attrByName('checking_source')[0] != null) {
    // определяем код временной таблицы
    def checking_source = instance.attrByName("checking_source")
    for (checking_src in checking_source) {
        def checking_source_obj = instanceService.getInstance(checking_src.id())
        checking_source1_type = checking_source_obj.getMetaClassName()
        if (checking_source1_type == "DQDBSource") {
            if (checking_source_obj.attrByName("dict_source_type") == 'api' || checking_source_obj.attrByName("dict_source_type") == 'kafka' || checking_source_obj.attrByName("dict_source_type") == 'file') {
                // для всего кроме БД - тип Общее, не проставлять линки на проверяемые объекты
                instance.replaceAttr('standardLogic', false)
                instance.replaceAttr('dq_type', 'other')
                dq_type = 'other'
                isStandard = false
                flg_setSourceLinks = 0
            }
        }
        if (checking_source1_type == "DQDBSource") {
            tmp_table_name = "dqstage." + checking_source_obj.attrByName('tmp_table_name')
        } else {
            trinoDbName = checkForTableIsFromTrino(checking_source_obj)
            if (trinoDbName) {
                tmp_table_name = trinoDbName + '.' + checking_source_obj.getParent().name + '.' + checking_source_obj.name
            } else {
                tmp_table_name = checking_source_obj.getParent().name + '.' + checking_source_obj.name
            }
        }
    }
}
if (isStandard && (oldVersion != null)) {
    def need_gen_select = 1
    def select_result_sql = ""
    def select_all_result_sql = ""
    if (instance.attrByName('check_template') != null && instance.attrByName('check_template')[0] != null) {
        instance.replaceAttr('dq_type', 'template')
        dq_type = 'template' // если шаблон заполнен - то тип принудительно - по Шаблону
    }
    def tmp_table_name2 = ""
    def trinoDbName2
    def checking_source1_type = ""
    def checking_source2_type = ""
    pk_list = ""
    col_list = ""
    if (instance.attrByName('checking_source') != null && instance.attrByName('checking_source')[0] != null) {
        if (dq_type !in ['length', 'count', 'count_compare', 'check_status']) {
            setSourceLinks(instance, "checking_source", "checking_table", "dbObjectLinkPKcol")
        }
        if (dq_type != 'other') { // проверка Не Прочее
            def checking_source = instance.attrByName("checking_source")
            def checking_source_id = -1
            def checking_source2_id = -1
            for (checking_src in checking_source) {
                def checking_source_obj = instanceService.getInstance(checking_src.id())
                checking_source_id = checking_src.id()
                checking_source1_type = checking_source_obj.getMetaClassName() // АК добавила
            }
            select_result_sql = " FROM " + tmp_table_name
            if (dq_type == 'two_fields_compare' || dq_type == 'count_compare') {
                // Если тип проверки сравнить два поля, сравнить количество
                dq_type_compare = instance.attrByName('dq_type_compare') // переменная тип сравнения
                if (dq_type_compare != null && dq_type_compare != "") {
                    setSourceLinks(instance, "checking_source2", "checking_table2", "dbObjectLinkPKcol2")
                    def checking_source2 = instance.attrByName("checking_source2")
                    if (instance.attrByName('checking_source2') != null && instance.attrByName('checking_source2')[0] != null) {
                        if ((checking_source != null || checking_source != "") && (checking_source2 != null || checking_source2 != "")) {
                            // Заполняем код второй временной таблицы
                            for (checking_src in checking_source2) {
                                def checking_source_obj = instanceService.getInstance(checking_src.id())
                                checking_source2_id = checking_src.id()
                                checking_source2_type = checking_source_obj.getMetaClassName()
                                if (checking_source2_type == "DQDBSource") {
                                    tmp_table_name2 = "dqstage." + checking_source_obj.attrByName('tmp_table_name')
                                } else {// АК сюда добавили условие для Трино для 2 таблицы
                                    trinoDbName2 = checkForTableIsFromTrino(checking_source_obj)
                                    if (trinoDbName2) {
                                        tmp_table_name2 = trinoDbName2 + '.' + checking_source_obj.getParent().name + '.' + checking_source_obj.name
                                    } else {
                                        tmp_table_name2 = checking_source_obj.getParent().name + '.' + checking_source_obj.name
                                    }
                                }
                            }
                            //if(((checking_source1_type != "DQDBSource") || (checking_source2_type != "DQDBSource")) && ((trinoDbName) && (trinoDbName2))
                            if ( // АК Надо поправить if чтобы он работал на "если НЕ (оба источника - Наборы) ИЛИ если НЕ (оба источника Таблицы Трино)
                                    (
                                            (checking_source1_type != "DQDBSource") && !(trinoDbName)
                                    ) || (
                                            (checking_source2_type != "DQDBSource") && !(trinoDbName2)
                                    ) || (
                                            (checking_source1_type == "DQDBSource") && (trinoDbName2)
                                    ) || (
                                            (checking_source2_type == "DQDBSource") && (trinoDbName)
                                    )

                            ) {
                                sendValidationError("checking_source", "Неправильно выбраны источники или тип проверки", "Выбранный тип проверки подразумевает сравнение двух наборов данных. Это можно сделать только для источников - наборов данных. Указывать напрямую таблицы Каталога данных недопустимо. Необходимо создать наборы данных в соответствующем разделе и их использовать для настройки проверки.")
                            } else if (dq_type == 'two_fields_compare') { // если тип сравнения - сравнение 2 полей

                                if (checking_source_id != checking_source2_id) {
                                    def sj = instance.attrByName('source_join_condition')
                                    if (sj == null || sj == "") { // АК вот тут иногда падает с internal error
                                        sendValidationError("source_join_condition", "Не заполнено условие объединения", "Добавлено несколько источников для проверки, необходимо указать условие их объединения, начиная с FROM. Название таблиц необходимо указывать в виде перемененных @S1 и @S2. Пример - FROM @S1 JOIN @S2 ON @S1.customer_id = @S2.customer_id")
                                    } else {
                                        sj = sj.text
                                        if (!sj.toUpperCase().contains("FROM")) {
                                            sendValidationError("source_join_condition", "Условия объединения источников не содержит FROM", "Необходимо указать условие их объединения, начиная с FROM. Название таблиц необходимо указывать в виде перемененных @S1 и @S2. Пример - FROM @S1 JOIN @S2 ON @S1.customer_id = @S2.customer_id")
                                        }
                                        if (!sj.toUpperCase().contains("JOIN")) {
                                            sendValidationError("source_join_condition", "Условия объединения источников не содержит JOIN", "Необходимо указать условие их объединения, начиная с FROM. Название таблиц необходимо указывать в виде перемененных @S1 и @S2. Пример - FROM @S1 JOIN @S2 ON @S1.customer_id = @S2.customer_id")
                                        }
                                        if (!sj.toUpperCase().contains("@S1")) {
                                            sendValidationError("source_join_condition", "Условия объединения источников не содержит @S1", "Необходимо указать условие их объединения, начиная с FROM. Название таблиц необходимо указывать в виде перемененных @S1 и @S2. Пример - FROM @S1 JOIN @S2 ON @S1.customer_id = @S2.customer_id")
                                        }
                                        if (!sj.toUpperCase().contains("@S2")) {
                                            sendValidationError("source_join_condition", "Условия объединения источников не содержит @S2", "Необходимо указать условие их объединения, начиная с FROM. Название таблиц необходимо указывать в виде перемененных @S1 и @S2. Пример - FROM @S1 JOIN @S2 ON @S1.customer_id = @S2.customer_id")
                                        }
                                        select_result_sql = instance.attrByName('source_join_condition').text()
                                    }
                                }
                                if (instance.attrByName('dbObjectLink') != null && instance.attrByName('dbObjectLink')[0] != null) {
                                    if (instance.attrByName('dbObjectLink2') != null && instance.attrByName('dbObjectLink2')[0] != null) {
                                        def col = instance.attrByName('dbObjectLink')[0]
                                        col_name = col.name()
                                        def col2 = instance.attrByName('dbObjectLink2')[0]
                                        col2_name = col2.name()
                                        select_all_result_sql = "SELECT * " + select_result_sql + " "
                                        select_result_sql = select_result_sql + '\n WHERE NOT (' + tmp_table_name + "." + col_name + " " + getCompareTypeSign(dq_type_compare) + " " + tmp_table_name2 + "." + col2_name + ')'
                                    } else {
                                        sendValidationError("dbObjectLink2", "Не заполнен второй Проверяемый объект данных", "Необходимо заполнить второй Проверяемый объект данных")
                                    }
                                } else {
                                    sendValidationError("dbObjectLink", "Не заполнен Проверяемый объект данных", "Необходимо заполнить Проверяемый объект данных")
                                }
                            } else if (dq_type == 'count_compare') { // тип - сравнение количества
                                need_gen_select = 0 // АК ????
                                select_result_sql = "with S1 as (select count(1) c1, 1 as id from @S1),\nS2 as (select count(1) c2, 1 as id from @S2)\n" +
                                        "select c1, c2 from S1 join S2 on s1.id = s2.id\nwhere not (c1 " + getCompareTypeSign(dq_type_compare) + " c2)"
                                select_result_sql = "SELECT c1 as count1, c2 as count2 \nFROM (" + select_result_sql + ") as c"
                                select_all_result_sql = "SELECT 1"
                                instance.replaceAttr('boundCondition1', 50)
                                instance.replaceAttr('boundCondition2', 100)
                            }
                        }
                    } else if (dq_type in ['two_fields_compare', 'count_compare']) {
                        sendValidationError("checking_source2", "Не заполнен Источник 2", "Необходимо заполнить Источник 2 для указанного типа проверки")
                    }
                } else if (dq_type in ['two_fields_compare', 'count_compare']) {
                    sendValidationError("dq_type_compare", "Не заполнен Тип сравнения", "Необходимо заполнить Тип сравнения для указанного типа проверки")
                }
            } else if (select_result_sql != "") {
                i1 = instance.attrByName('intervalStartStr')
                i2 = instance.attrByName('intervalFinishStr')
                select_all_result_sql = "SELECT * " + select_result_sql + " "
                if (instance.attrByName('dbObjectLink') != null && instance.attrByName('dbObjectLink')[0] != null) {
                    def col = instance.attrByName('dbObjectLink')[0]
                    col_name = col.name()
                    def col_obj = instanceService.getInstance(col.id())
                    if (pk_list != "") {
                        select_all_result_sql = "SELECT " + pk_list + ", " + col_name + select_result_sql + " "
                    } else {
                        select_all_result_sql = "SELECT * " + select_result_sql + " "
                    }
                    ClassInstance col_inst = instanceService.getInstance(col.id())
                    def tbl_inst = col_inst.getParent()
                    //col_name = tbl_inst.name() + '.' + col_name
                    if (dq_type == 'notnull') { // тип проверки - не NULL
                        select_result_sql = select_result_sql + '\n WHERE ' + col_name + ' IS NULL'
                    }
                    if (dq_type == 'interval') { // тип проверки - интервал
                        if (i1 == null || i1 == "" || i2 == null || i2 == "") {
                            sendValidationError("intervalStartStr", "Не заполнены границы диапазона", "Необходимо указать границы диапазона")
                        } else {
                            select_result_sql = select_result_sql + '\n WHERE ' + col_name + ' NOT BETWEEN ' + i1.toString() + ' AND ' + i2.toString()
                        }
                    }
                    if (dq_type == 'gt') { // тип проверки - больше чем
                        if (i1 == null || i1 == "") {
                            sendValidationError("intervalStartStr", "Не заполнено Начало диапазона", "Необходимо указать Не заполнено Начало диапазона")
                        } else {
                            select_result_sql = select_result_sql + '\n WHERE NOT (' + col_name + ' > ' + i1.toString() + ')'
                        }
                    }
                    if (dq_type == 'lt') { // тип проверки - меньше чем
                        if (i2 == null || i2 == "") {
                            sendValidationError("intervalFinishStr", "Не заполнено Окончание диапазона", "Необходимо указать Окончание диапазона")
                        } else {
                            select_result_sql = select_result_sql + '\n WHERE NOT (' + col_name + ' < ' + i2.toString() + ')'
                        }
                    }
                    if (dq_type == 'gteq') { // тип проверки - больше чем или равно
                        if (i1 == null || i1 == "") {
                            sendValidationError("intervalStartStr", "Не заполнено Начало диапазона", "Необходимо указать Не заполнено Начало диапазона")
                        } else {
                            select_result_sql = select_result_sql + '\n WHERE NOT (' + col_name + ' >= ' + i1.toString() + ')'
                        }
                    }
                    if (dq_type == 'lteq') { // тип проверки - меньше чем или равно
                        if (i2 == null || i2 == "") {
                            sendValidationError("intervalFinishStr", "Не заполнено Окончание диапазона", "Необходимо указать Окончание диапазона")
                        } else {
                            select_result_sql = select_result_sql + '\n WHERE NOT (' + col_name + ' <= ' + i2.toString() + ')'
                        }
                    }
                    if (dq_type == 'length') { // тип проверки - длина строки
                        if (i1 == null || i1 == "" || i2 == null || i2 == "") {
                            sendValidationError("intervalStartStr", "Не заполнены границы диапазона", "Необходимо указать границы диапазона")
                        } else {
                            if (!i1.isInteger() || !i2.isInteger()) {
                                sendValidationError("intervalStartStr", "Границы диапазона должны быть числовыми", "Необходимо указать числовые границы диапазона")
                            } else {
                                select_result_sql = select_result_sql + '\n WHERE LENGTH(TRIM(' + col_name + '::text)) NOT BETWEEN ' + i1.toString() + ' AND ' + i2.toString()
                            }
                        }
                    }
                    if (dq_type == 'count') { // тип - количество записей
                        if (i1 == null || i1 == "" || i2 == null || i2 == "") {
                            sendValidationError("intervalStartStr", "Не заполнены границы диапазона", "Необходимо указать границы диапазона")
                        } else {
                            if (!i1.isInteger() || !i2.isInteger()) {
                                sendValidationError("intervalStartStr", "Границы диапазона должны быть числовыми", "Необходимо указать числовые границы диапазона")
                            } else {
                                need_gen_select = 0
                                select_result_sql = "SELECT cnt FROM (SELECT COUNT(*) as cnt " + select_result_sql + ") as c WHERE c.cnt NOT BETWEEN " + i1.toString() + " AND " + i2.toString()
                                select_all_result_sql = "SELECT 1"
                                instance.replaceAttr('boundCondition1', 50)
                                instance.replaceAttr('boundCondition2', 100)
                            }
                        }
                    }
                    if (dq_type == 'unique') { // тип - уникальность
                        need_gen_select = 0
                        if (CheckPKColumns(instance) == 1) {
                            def dbObjectLinkPKcol_field = "dbObjectLinkPKcol"
                            def unique_cols = getCommaSeparatedColumns(instance, "dbObjectLink", tmp_table_name)
                            def pk_cols = instance.attrByName(dbObjectLinkPKcol_field)
                            select_result_sql = " SELECT " + unique_cols + ",  count(" + col_name + ") as cnt, string_agg(" +
                                    pk_cols[0].name + "::text, ',') as " + pk_cols[0].name + " " + select_result_sql +
                                    " group by " + unique_cols + " having count(*)>1"
                        }
                    }
                    if (dq_type == 'template') { // тип - темплейт
                        need_gen_select = 1
                        if (instance.attrByName('check_template') != null && instance.attrByName('check_template')[0] != null) {
                            def check_template = instance.attrByName("check_template")
                            for (check_template_ref in check_template) {
                                def check_template_obj = instanceService.getInstance(check_template_ref.id())
                                def template_script = check_template_obj.attrByName("template_script").text
                                def params = instance.attrByName('templateParams')
                                def params_tmpl = check_template_obj.attrByName('inputParams')
                                if (params != null && params.size() > 0) {
                                    for (p in params) {
                                        template_script = template_script.replace(p.templateParamKey, p.templateParamValue)
                                    }
                                    setCheckedDbObjectByTemplate(instance, "checking_source", "checking_table", "dbObjectLink", params)
                                } else {
                                    def i = 0
                                    for (p in params_tmpl) {
                                        def col_name = ""
                                        if (instance.attrByName('dbObjectLink')[i] != null) {
                                            col_name = instance.attrByName('dbObjectLink')[i].name
                                        }
                                        def p1 = ["templateParamKey": p.inputParamCode, "templateParamValue": col_name]
                                        if (params == null) {
                                            params = []
                                        }
                                        params.add(p1)
                                        template_script = template_script.replace(p1.templateParamKey, p1.templateParamValue)
                                        i++
                                    }
                                    instance.replaceAttr('templateParams', params)
                                }
                                select_result_sql = select_result_sql + '\n WHERE NOT (' + template_script + ')'
                            }
                        } else {
                            sendValidationError("check_template", "Не указан шаблон проверки", "Необходимо указать шаблон проверки и соответствие параметров шаблона")
                        }

                    }
                } else {
                    if (dq_type !in ['length', 'count', 'count_compare', 'check_status']) {
                        sendValidationError("dbObjectLink", "Не заполнен Проверяемый объект данных", "Необходимо заполнить Проверяемый объект данных")
                    }
                }
            }
            if (need_gen_select == 1) {  // этот атрибут означает что мы очень интересуемся колонкой с ключами
                if (instance.attrByName('dbObjectLinkPKcol') != null && instance.attrByName('dbObjectLinkPKcol')[0] != null) {
                    select_result_sql = " SELECT " + getCommaSeparatedColumns(instance, "dbObjectLinkPKcol", tmp_table_name) + ", " + getCommaSeparatedColumns(instance, "dbObjectLink", tmp_table_name) + " " + select_result_sql
                } // вот тут отбираем какие колонки идут в селект АК надо поправить от повторений
                else if (dq_type !in ['length', 'count', 'count_compare', 'check_status']) {
                    sendValidationError("dbObjectLinkPKcol", "Не указан Идентификатор записи", "Необходимо указать поле (поля), составляющие Идентификатор записи (Первичный ключ) для источника данных. Он используется для фиксации в журнал информации об ошибочной записи.")
                }
            } // вот тут замены переменных 
            instance.replaceAttr('full_select_script', new LanguageType(select_all_result_sql.replace("@S1", tmp_table_name).replace("@S2", tmp_table_name2), 'sql'))
            instance.replaceAttr('full_bad_records_script', new LanguageType(select_result_sql.replace("@S1", tmp_table_name).replace("@S2", tmp_table_name2), 'sql'))
        } else {

        }
    } else {
        sendValidationError("checking_source", "Не заполнен Источник данных", "Необходимо заполнить Источник данных")
    }
} else {
    if (instance.attrByName('full_bad_records_script') != null && instance.attrByName('full_bad_records_script') != "") {
        instance.replaceAttr('full_bad_records_script', new LanguageType(instance.attrByName('full_bad_records_script').text.replace("STATUS", "status"), 'sql'))
    }
    if (flg_setSourceLinks == 1) {
        if (dq_type !in ['length', 'count', 'count_compare', 'check_status']) {
            setSourceLinks(instance, "checking_source", "checking_table", "dbObjectLinkPKcol")
        }
        if (dq_type == 'two_fields_compare') {
            setSourceLinks(instance, "checking_source2", "checking_table2", "dbObjectLinkPKcol2")
        }
    }
}
if ((oldVersion != null) && oldVersion.attrByName('status') != instance.attrByName('status')) {
    if (instance.attrByName('full_bad_records_script') == null || instance.attrByName('full_bad_records_script').text == "") {
        sendValidationError("full_bad_records_script", "Не заполнен Скрипт отбора данных", "Необходимо заполнить Скрипт отбора данных")
    }
}

