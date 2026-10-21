const authToken = () => localStorage.getItem("authToken");
const auth_t = authToken();

const decodeJWTManually = (token) => {
  try {
    const base64Url = token.split('.')[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const jsonPayload = decodeURIComponent(atob(base64).split('').map(function (c) {
      return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
    }).join(''));

    return JSON.parse(jsonPayload);
  } catch (e) {
    console.error("Error decoding JWT manually:", e);
    return null;
  }
};

(function () {
  const params = new URLSearchParams(window.location.search);

  const needDownload = params.get('downloadDqReport') === '1';
  const dqImplCode = params.get('dqImplCode');
  const dqStatId = params.get('dqStatId');

  if (!needDownload || !dqImplCode || !dqStatId) {
    return;
  }

  const downloadUrl =
    `/api/file/download/dqReport?dqImplCode=${encodeURIComponent(dqImplCode)}&dqStatId=${encodeURIComponent(dqStatId)}`;

  const link = document.createElement('a');
  link.href = downloadUrl;
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);

  params.delete('downloadDqReport');
  params.delete('dqImplCode');
  params.delete('dqStatId');

  const cleanUrl =
    window.location.pathname +
    (params.toString() ? `?${params.toString()}` : '') +
    window.location.hash;

  window.history.replaceState({}, document.title, cleanUrl);
})();

function App() {
  var btn_edit = document.querySelector('button[data-element="EntityEdit"]');

  const DataOcean = window.DataOcean;
  const fields = DataOcean.fieldManager.fields;
  const stat = DataOcean.entityStore?.entity?.status;

  if (stat == '30') {
    btn_edit.style.display = 'none';
  }

  function getFieldContexts(fieldNames) {
    const fieldContexts = {};
    fieldNames.forEach(fieldName => {
      fieldContexts[fieldName] = fields.find((i) => i.field === fieldName)?.contextManager;
    });
    return fieldContexts;
  }

  const clearProps = (fieldContexts, fieldNames) => {
    fieldNames.forEach((fieldName) => {
      fieldContexts[fieldName]?.clearProps();
    });
  };

  const dqi_type_init = DataOcean.entityStore.entity.dq_run_type;

  function makeObservable(target, onChange) {
    return new Proxy(target, {
      set(obj, prop, value) {
        if (prop === 'mode' && obj[prop] !== value) {
          const oldValue = obj[prop];
          obj[prop] = value;
          onChange(value, oldValue);
          return true;
        }
        obj[prop] = value;
        return true;
      },
      get(obj, prop) {
        return obj[prop];
      }
    });
  }

  const originalState = DataOcean.entityStore.state;
  DataOcean.entityStore.state = makeObservable(originalState, (newVal, oldVal) => {
    if (newVal == 'edit') {
      switch (dqi_type_init) {
        case "scheduled":
          DataOcean.fieldManager.get('dq_period_cron').dq_period_cron.setProps({ disabled: false, readOnly: false });
          break;
        case "after_update":
          DataOcean.fieldManager.get('UpdateRun').UpdateRun.setProps({ disabled: false, readOnly: false });
          break;
        case "related":
          DataOcean.fieldManager.get('linked_dqi').linked_dqi.setProps({ disabled: false, readOnly: false });
          DataOcean.fieldManager.get('errors_quantity').errors_quantity.setProps({ disabled: false, readOnly: false });
          break;
      }
    }
  });

  const fetchSourceData = async (sourceObj) => {
    const obj = Array.isArray(sourceObj) ? sourceObj[0] : sourceObj;

    if (obj?.type === "DQDBSource") {
      const resp = await fetch(`/api/entity/public/DQDBSource/${obj.id}`, {
        method: "GET",
        headers: {
          Authorization: `Bearer ${auth_t}`,
          Accept: "application/json",
          "Content-type": "application/json; charset=utf-8",
        },
      });
      const json = await resp.json();

      const tmp_table_name = await DataOcean.api.post('/search?searchString=' + json.tmp_table_name + '3&limit=10&offset=0&objectTypes=Table&deepSearch=false&glossaryMode=public&quickSearch=true');
      const sr = tmp_table_name.data?.searchResults;
      if (sr) {
        return sr[0].businessId;
      }

      return json.dbObjectLink[0].id;
    }
    else if (obj?.type === "Table") {
      return obj?.id;
    }
    return obj?.id || -1;
  };

  DataOcean.subscriptions.subscribe({
    fieldNames: [
      "standardLogic", "dq_type", "dq_type_compare", "dq_period_cron", "checking_source",
      "checking_source2", "templateParams", "check_template", "dbTable", "dbObjectLinkReq",
      "dq_run_type"
    ],
    callback: async ({ value, setField }) => {
      const fieldNames = [
        "standardLogic", "dq_type", "full_bad_records_script", "full_select_script",
        "templateParams", "check_template", "dbObjectLink", "dbObjectLinkPKcol",
        "intervalStartStr", "intervalFinishStr", "dq_period_cron", "dq_period",
        "checking_table", "checking_source2", "checking_source", "checking_table2",
        "dbObjectLink2", "dbObjectLinkPKcol2", "dq_type_compare", "source_join_condition",
        "dbTable", "dbObjectLinkReq",
        "UpdateRun", "linked_dqi", "errors_quantity"
      ];

      const fieldContexts = getFieldContexts(fieldNames);

      const setPropsForFields = (fields, props) => {
        fields.forEach(field => fieldContexts[field]?.setProps(props));
      };

      const disableFields = (fields) => {
        setPropsForFields(fields, { disabled: true, value: null });
      };

      const enableFieldsAndSetParentId = (fields, parentId) => {
        setPropsForFields(fields, { disabled: false, parentId });
      };

      clearProps(fieldContexts, ["full_bad_records_script", "full_select_script", "source_join_condition"]);

      const checking_table_id = await fetchSourceData(value.checking_source);
      const checking_table2_id = await fetchSourceData(value.checking_source2);
      const dbTable_id = await fetchSourceData(value.dbTable);

      if (checking_table_id > 0) {
        fieldContexts.dbObjectLink?.setProps({ parentId: checking_table_id });
        fieldContexts.dbObjectLinkPKcol?.setProps({ parentId: checking_table_id });
      }

      if (checking_table2_id > 0) {
        fieldContexts.dbObjectLink2?.setProps({ parentId: checking_table2_id });
        fieldContexts.dbObjectLinkPKcol2?.setProps({ parentId: checking_table2_id });
      }

      if (dbTable_id > 0) {
        fieldContexts.dbObjectLinkReq?.setProps({ parentId: dbTable_id });
      }

      if (!fieldContexts.standardLogic) return;

      if (value.standardLogic === true) {
        fieldContexts.full_bad_records_script?.setProps({ disabled: true });
        fieldContexts.full_select_script?.setProps({ disabled: true });
      } else {
        clearProps(fieldContexts, ["full_bad_records_script", "full_select_script"]);
      }

      const tp = value.dq_type;

      if (DataOcean.entityStore.state.mode == 'edit') {
        const dq_run_type = value.dq_run_type;
        switch (dq_run_type) {
          case "scheduled":
            fieldContexts.dq_period_cron?.setProps({ disabled: false });
            disableFields(["UpdateRun", "linked_dqi", "errors_quantity"]);
            setField('UpdateRun', false);
            break;
          case "after_update":
            fieldContexts.UpdateRun?.setProps({ disabled: false });
            disableFields(["dq_period_cron", "linked_dqi", "errors_quantity"]);
            break;
          case "related":
            fieldContexts.linked_dqi?.setProps({ disabled: false });
            fieldContexts.errors_quantity?.setProps({ disabled: false });
            disableFields(["dq_period_cron", "UpdateRun"]);
            setField('UpdateRun', false);
            break;
        }
      }

      switch (tp) {
        case "notnull":
          enableFieldsAndSetParentId(["dbObjectLink", "dbObjectLinkPKcol"], checking_table_id);
          disableFields(["dbObjectLink2", "dbObjectLinkPKcol2", "intervalStartStr", "intervalFinishStr", "checking_source2", "dq_type_compare", "source_join_condition"]);
          fieldContexts.standardLogic?.clearProps();
          break;
        case "interval":
        case "length":
          fieldContexts.intervalStartStr?.setProps({ disabled: false });
          fieldContexts.intervalFinishStr?.setProps({ disabled: false });
          disableFields(["checking_source2", "dbObjectLink2", "dbObjectLinkPKcol2", "dq_type_compare", "source_join_condition"]);
          fieldContexts.standardLogic?.clearProps();
          break;
        case "count":
          fieldContexts.intervalStartStr?.setProps({ disabled: false });
          fieldContexts.intervalFinishStr?.setProps({ disabled: false });
          disableFields(["dbObjectLink", "dbObjectLinkPKcol", "checking_source2", "dbObjectLink2", "dbObjectLinkPKcol2", "dq_type_compare", "source_join_condition"]);
          fieldContexts.standardLogic?.clearProps();
          break;
        case "gt":
        case "gteq":
          enableFieldsAndSetParentId(["dbObjectLink", "dbObjectLinkPKcol"], checking_table_id);
          fieldContexts.intervalStartStr?.setProps({ disabled: false });
          disableFields(["intervalFinishStr", "checking_source2", "dbObjectLink2", "dbObjectLinkPKcol2", "dq_type_compare", "source_join_condition"]);
          fieldContexts.standardLogic?.clearProps();
          break;
        case "lt":
        case "lteq":
          enableFieldsAndSetParentId(["dbObjectLink", "dbObjectLinkPKcol"], checking_table_id);
          fieldContexts.intervalFinishStr?.setProps({ disabled: false });
          disableFields(["intervalStartStr", "checking_source2", "dbObjectLink2", "dbObjectLinkPKcol2", "dq_type_compare", "source_join_condition"]);
          fieldContexts.standardLogic?.clearProps();
          break;
        case "unique":
          enableFieldsAndSetParentId(["dbObjectLink", "dbObjectLinkPKcol"], checking_table_id);
          disableFields(["intervalStartStr", "intervalFinishStr", "checking_source2", "dbObjectLink2", "dbObjectLinkPKcol2", "dq_type_compare", "source_join_condition"]);
          fieldContexts.standardLogic?.clearProps();
          break;
        case "other":
          enableFieldsAndSetParentId(["dbObjectLink", "dbObjectLinkPKcol"], checking_table_id);
          disableFields(["intervalStartStr", "intervalFinishStr", "checking_source2", "dbObjectLink2", "dbObjectLinkPKcol2", "dq_type_compare", "source_join_condition"]);
          fieldContexts.standardLogic?.setProps({ value: false });
          fieldContexts.full_bad_records_script?.setProps({ disabled: false });
          fieldContexts.full_select_script?.setProps({ disabled: false });
          break;
        case "two_fields_compare":
          enableFieldsAndSetParentId(["dbObjectLink", "dbObjectLinkPKcol"], checking_table_id);
          disableFields(["intervalStartStr", "intervalFinishStr"]);
          fieldContexts.standardLogic?.clearProps();
          fieldContexts.full_bad_records_script?.setProps({ disabled: true });
          fieldContexts.full_select_script?.setProps({ disabled: true });
          fieldContexts.checking_source2?.setProps({ disabled: false });
          enableFieldsAndSetParentId(["dbObjectLink2", "dbObjectLinkPKcol2"], checking_table2_id);
          fieldContexts.source_join_condition?.setProps({ disabled: false });
          fieldContexts.dq_type_compare?.setProps({ disabled: false });
          if (value.standardLogic === true) {
            fieldContexts.full_bad_records_script?.setProps({ disabled: true });
            fieldContexts.full_select_script?.setProps({ disabled: true });
          } else {
            clearProps(fieldContexts, ["full_bad_records_script", "full_select_script"]);
          }
          break;
        case "count_compare":
          disableFields(["dbObjectLink", "dbObjectLinkPKcol", "intervalStartStr", "intervalFinishStr", "dbObjectLink2", "dbObjectLinkPKcol2", "source_join_condition"]);
          fieldContexts.standardLogic?.clearProps();
          fieldContexts.full_bad_records_script?.setProps({ disabled: true });
          fieldContexts.full_select_script?.setProps({ disabled: true });
          fieldContexts.checking_source2?.setProps({ disabled: false });
          fieldContexts.dq_type_compare?.setProps({ disabled: false });
          break;
        case "template":
          disableFields(["intervalStartStr", "intervalFinishStr", "dq_type_compare", "source_join_condition", "dbObjectLink2", "dbObjectLinkPKcol2", "checking_source2"]);
          break;
        case "check_status":
          enableFieldsAndSetParentId(["dbObjectLink"], checking_table_id);
          disableFields(["full_select_script", "intervalStartStr", "dbObjectLinkPKcol", "intervalFinishStr", "checking_source2", "dbObjectLink2", "dbObjectLinkPKcol2", "dq_type_compare", "source_join_condition"]);
          fieldContexts.standardLogic?.setProps({ value: false });
          fieldContexts.full_bad_records_script?.setProps({ disabled: false });
          break;
        default:
          clearProps(fieldContexts, ["dbObjectLink", "dbObjectLinkPKcol", "intervalStartStr", "intervalFinishStr", "standardLogic", "checking_source2", "dbObjectLink2", "dbObjectLinkPKcol2", "source_join_condition"]);
          enableFieldsAndSetParentId(["dbObjectLink", "dbObjectLinkPKcol"], checking_table_id);
          enableFieldsAndSetParentId(["dbObjectLink2", "dbObjectLinkPKcol2"], checking_table2_id);
      }
    }
  });
}

try {
  App.apply(window);
} catch (e) {
  console.error(e);
}
import('/portal/static/feedback.js');
