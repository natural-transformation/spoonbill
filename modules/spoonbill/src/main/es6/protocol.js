// Generated from Frontend.scala by JsUtils.clientProtocol; do not edit.

/** @enum {number} */
export const Procedure = {
  SET_EVENT_COUNTER: 0,
  RELOAD: 1,
  LISTEN_EVENT: 2,
  EXTRACT_PROPERTY: 3,
  MODIFY_DOM: 4,
  FOCUS: 5,
  CHANGE_PAGE_URL: 6,
  UPLOAD_FORM: 7,
  RELOAD_CSS: 8,
  KEEP_ALIVE: 9,
  EVAL_JS: 10,
  EXTRACT_EVENT_DATA: 11,
  LIST_FILES: 12,
  UPLOAD_FILE: 13,
  REST_FORM: 14,
  DOWNLOAD_FILE: 15,
  HEARTBEAT: 16,
  RESET_EVENT_COUNTERS: 17,
  COMPLETE_AUTHENTICATION: 18,
  RESET_VIEW: 19,
  PATCH_VIEW: 20,
  READY_FOR_USER_EVENTS: 21,
  PRESENT_SENSITIVE: 22,
  CLEAR_SENSITIVE: 23,
  SENSITIVE_NAVIGATION_COMPLETE: 24,
  SENSITIVE_DEPARTURE_COMPLETE: 25,
  RECOVER_AUTHENTICATION: 26
};

/** @enum {number} */
export const CallbackType = {
  DOM_EVENT: 0,
  CUSTOM_CALLBACK: 1,
  EXTRACT_PROPERTY_RESPONSE: 2,
  HISTORY: 3,
  EVALJS_RESPONSE: 4,
  EXTRACT_EVENT_DATA_RESPONSE: 5,
  HEARTBEAT: 6,
  VIEW_EVENT: 7,
  SENSITIVE_ACK: 8,
  SENSITIVE_CLEARED: 9,
  SENSITIVE_DEPARTURE: 10
};

/** @enum {number} */
export const PropertyType = {
  STRING: 0,
  NUMBER: 1,
  BOOLEAN: 2,
  OBJECT: 3,
  ERROR: 4
};

/** @enum {number} */
export const ModifyDomProcedure = {
  CREATE: 0,
  CREATE_TEXT: 1,
  REMOVE: 2,
  SET_ATTR: 3,
  REMOVE_ATTR: 4,
  SET_STYLE: 5,
  REMOVE_STYLE: 6
};

export function domOperandWidths() { return [4, 3, 2, 5, 4, 3, 2]; }
