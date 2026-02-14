import { encodeRFC5987ValueChars } from './utils.js';

/** @enum {number} */
export const CallbackType = {
  DOM_EVENT: 0, // `$eventCounter:$elementId:$eventType`
  CUSTOM_CALLBACK: 1, // `$name:$arg`
  EXTRACT_PROPERTY_RESPONSE: 2, // `$descriptor:$propertyType:$value`
  HISTORY: 3, // URL
  EVALJS_RESPONSE: 4, // `$descriptor:$status:$value`
  EXTRACT_EVENT_DATA_RESPONSE: 5, // `$descriptor:$dataJson`
  HEARTBEAT: 6 // `$descriptor`
};

/** @enum {number} */
export const PropertyType = {
  STRING: 0,
  NUMBER: 1,
  BOOLEAN: 2,
  OBJECT: 3,
  ERROR: 4
};

function eventCounterKey(id, eventType) {
  return `${id}_${eventType}`;
}

export class Spoonbill {

  /**
   * @param {Object} config
   * @param {function(CallbackType, string)} callback
   */
  constructor(config, callback) {
    /** @type {Object} */
    this.config = config;
    /** @type {HTMLElement} */
    this.root = document.children[0];
    /** @type {Object<?Node>} */
    this.els = {};
    /** @type {Object<number>} */
    this.eventCounters = {};
    /** @type {Array} */
    this.rootListeners = [];
    /** @type {?function(Event)} */
    this.historyHandler = null;
    /** @type {string} */
    this.initialPath = window.location.pathname;
    /** @type {function(CallbackType, string)} */
    this.callback = callback;
    /** @type {Object<Event>} */
    this.eventData = {};

    this.listenRoot = (name, preventDefault) => {
      var listener = (event) => {
        // Prefer composedPath() to handle Shadow DOM or extension-injected nodes.
        // Fallback to walking up the parent chain.
        let target = null;
        const path =
          (event.composedPath && event.composedPath()) ||
          event.path ||
          null;
        if (path && path.length) {
          for (let i = 0; i < path.length; i++) {
            const node = path[i];
            if (node && node.nodeType === 1 && node.vId) {
              target = node;
              break;
            }
          }
        } else {
          let node = event.target;
          while (node && (node.nodeType !== 1 || !node.vId)) {
            node = node.parentNode;
          }
          target = node;
        }
        if (preventDefault) {
          event.preventDefault();
        }
        if (target && target.vId) {
          let ecKey = eventCounterKey(target.vId, event.type);
          let ec = this.eventCounters[ecKey] ?? 0;
          this.eventData[ecKey] = event;
          this.callback(CallbackType.DOM_EVENT, ec + ':' + target.vId + ':' + event.type);
        }
      };
      // Attach to document to avoid missing bubbling events on <html>.
      // Use capture for submit to handle browsers where submit doesn't bubble reliably.
      let useCapture = name === 'submit';
      let target = (document || this.root);
      target.addEventListener(name, listener, useCapture);
      // Track target and capture to remove the same listener on destroy.
      this.rootListeners.push({ 'listener': listener, 'type': name, 'target': target, 'capture': useCapture });
    };

    this.listenRoot('submit', true);

    this.historyHandler = (/** @type {Event} */ event) => {
      if (event.state === null) callback(CallbackType.HISTORY, this.initialPath);
      else callback(CallbackType.HISTORY, event.state);
    };

    this.windowHandler = (/** @type {Event} */ event) => {
      // 1 - event for top level element only ('body)
      let ecKey = eventCounterKey('1', event.type);
      this.eventData[ecKey] = event;
      const ec = this.eventCounters[ecKey] ?? 0;
      callback(CallbackType.DOM_EVENT, ec + ':1:' + event.type);
    };

    window.addEventListener('popstate', this.historyHandler);
    window.addEventListener('resize', this.windowHandler);
  }

  swapElementInRegistry(a, b) {
    b.vId = a.vId;
    this.els[a.vId] = b;
  }

  destroy() {
    // Remove root listeners
    this.rootListeners.forEach((o) => {
      let target = o.target || this.root;
      target.removeEventListener(o.type, o.listener, o.capture);
    });
    // Remove popstate handler
    window.removeEventListener('popstate', this.historyHandler);
    window.removeEventListener('resize', this.windowHandler);
  }

  /** 
   * @param {string} elementVId
   * @param {string} eventType
   * @param {number} n
   */
  setEventCounter(elementVId, eventType, n) {
    // Remove obsolete event data
    let ecKey = eventCounterKey(elementVId, eventType)
    delete this.eventData[ecKey];
    this.eventCounters[ecKey] = n;
  }

  resetEventCounters() {
    this.eventData = {};
    this.eventCounters = {};
  }

  /** @param {?HTMLElement} rootNode */
  registerRoot(rootNode) {
    if (rootNode == null) return;
    let self = this;
    function aux(prefix, node) {
      var children = node.childNodes;
      var lastId = 0

      for (var i = 0; i < children.length; i++) {
        var child = children[i];
        var id

        if(self.config['kid']) {
          if(child.getAttribute && child.getAttribute('k')) {
            id = prefix + '_' + child.getAttribute('k');
            lastId = child.getAttribute('k');
          }else {
            lastId = lastId + 1;
            id = prefix + '_' + lastId;
          }
        } else {
          id = prefix + '_' + (i + 1);
        }

        child.vId = id;
        self.els[id] = child;
        aux(id, child);
      }
    }

    self.root = rootNode;
    self.els["1"] = rootNode;
    aux("1", rootNode);
  }

   /**
    * @param {string} type
    * @param {boolean} preventDefault
    */
  listenEvent(type, preventDefault) {
    this.listenRoot(type, preventDefault);
  }

  /**
   * @param {Array} data
   */
  modifyDom(data) {
    // Reverse data to use pop() instead of shift()
    // pop() faster than shift()
    let atad = data.reverse();
    let r = atad.pop.bind(atad);
    while (data.length > 0) {
      switch (r()) {
        case 0: this.create(r(), r(), r(), r()); break;
        case 1: this.createText(r(), r(), r()); break;
        case 2: this.remove(r(), r()); break;
        case 3: this.setAttr(r(), r(), r(), r(), r()); break;
        case 4: this.removeAttr(r(), r(), r(), r()); break;
        case 5: this.setStyle(r(), r(), r()); break;
        case 6: this.removeStyle(r(), r()); break;
      }
    }
  }

   /**
    * @param {string} id
    * @param {string} childId
    * @param {string} tag
    */
  create(id, childId, xmlNs, tag) {
    var parent = this.els[id],
      child = this.els[childId],
      newElement;
    if (!parent) return;
    if (xmlNs === 0) {
      newElement = document.createElement(tag);
    } else {
      newElement = document.createElementNS(xmlNs, tag);
    }
    newElement.vId = childId;
    if(this.config['kid']) {
      var ids = childId.split('_')
      newElement.setAttribute('k', ids[ids.length - 1]);
    }
    if (child && child.parentNode === parent) {
      parent.replaceChild(newElement, child);
    } else {
      parent.appendChild(newElement);
    }
    this.els[childId] = newElement;
  }

   /**
    * @param {string} id
    * @param {string} childId
    * @param {string} text
    */
  createText(id, childId, text) {
    var parent = this.els[id],
      child = this.els[childId],
      newElement;
    if (!parent) return;
    newElement = document.createTextNode(text);
    newElement.vId = childId;
    if (child && child.parentNode === parent) {
      parent.replaceChild(newElement, child);
    } else {
      parent.appendChild(newElement);
    }
    this.els[childId] = newElement;
  }

   /**
    * @param {string} id
    * @param {string} childId
    */
  remove(id, childId) {
    var parent = this.els[id],
      child = this.els[childId];
    if (!parent) return;
    if (child) {
      parent.removeChild(child);
    }
  }

   /**
    * @param {string} descriptor
    * @param {string} id
    * @param {string} propertyName
    */
  extractProperty(descriptor, id, propertyName) {
    let element = this.els[id];
    if (!element) {
      this.callback(
        CallbackType.EXTRACT_PROPERTY_RESPONSE,
        `${descriptor}:${PropertyType.ERROR}:${id} is missing`
      );
      return;
    }
    let value;
    try {
      value = element[propertyName];
    } catch (error) {
      this.callback(
        CallbackType.EXTRACT_PROPERTY_RESPONSE,
        `${descriptor}:${PropertyType.ERROR}:${propertyName} threw`
      );
      return;
    }
    var result, type;
    switch (typeof value) {
      case 'undefined':
        type = PropertyType.ERROR;
        result = `${propertyName} is undefined`;
        break;
      case 'function':
        type = PropertyType.ERROR;
        result = `${propertyName} is a function`;
        break;
      case 'object':
        type = PropertyType.OBJECT;
        result = JSON.stringify(value);
        break;
      case 'string':
        type = PropertyType.STRING;
        result = value;
        break;
      case 'number':
        type = PropertyType.NUMBER;
        result = value;
        break;
      case 'boolean':
        type = PropertyType.BOOLEAN;
        result = value;
        break;
    }
    this.callback(
      CallbackType.EXTRACT_PROPERTY_RESPONSE,
      `${descriptor}:${type}:${result}`
    );
  }

   /**
    * @param {string} id
    * @param {string} name
    * @param {string} value
    * @param {boolean} isProperty
    */
  setAttr(id, xmlNs, name, value, isProperty) {
    var element = this.els[id];
    if (isProperty) element[name] = value;
    else if (xmlNs === 0) {
      element.setAttribute(name, value);
    } else {
      element.setAttributeNS(xmlNs, name, value);
    }
  }

   /**
    * @param {string} id
    * @param {string} name
    * @param {boolean} isProperty
    */
  removeAttr(id, xmlNs, name, isProperty) {
    var element = this.els[id];
    if (isProperty) element[name] = undefined;
    else if (xmlNs === 0) {
      element.removeAttribute(name);
    } else {
      element.removeAttributeNS(xmlNs, name);
    }
  }

   /**
    * @param {string} id
    * @param {string} name
    * @param {string} value
    */
  setStyle(id, name, value) {
    var element = this.els[id];
    element.style.setProperty(name, value);
  }

   /**
    * @param {string} id
    * @param {string} name
    */
  removeStyle(id, name) {
    var element = this.els[id];
    element.style.removeProperty(name);
  }

   /**
    * @param {string} id
    */
  focus(id) {
    setTimeout(() => {
      var element = this.els[id];
      element.focus();
    }, 0);
  }

   /**
    * @param {string} id
    */
  element(id) {
    return this.els[id];
  }

   /**
    * @param {string} path
    */
  changePageUrl(path) {
    if (path !== window.location.pathname + window.location.search)
      window.history.pushState(path, '', path);
  }

   /**
    * @param {string} name
    * @param {string} arg
    */
  invokeCustomCallback(name, arg) {
    this.callback(CallbackType.CUSTOM_CALLBACK, [name, arg].join(':'));
  }

   /**
    * @param {string} id
    * @param {string} descriptor
    */
  uploadForm(id, descriptor) {
    let self = this;
    var form = self.els[id];
    if (!(form instanceof HTMLFormElement)) return;
    var formData = new FormData(form);
    var request = new XMLHttpRequest();
    var uri = self.config['r'] +
      'bridge' +
      '/' + self.config['sid'] +
      '/form-data' +
      '/' + descriptor;
    request.open("POST", uri, true);
//    request.upload.onprogress = function(event) {
//      var arg = [descriptor, event.loaded, event.total].join(':');
//      self.callback(CallbackType.FORM_DATA_PROGRESS, arg);
//    };
    request.send(formData);
  }

  /**
    * @param {string} id
    * @param {string} descriptor
    */
  listFiles(id, descriptor) {
    let self = this;
    let input = self.els[id];
    let files = [];
    let uri = self.config['r'] +
      'bridge' +
      '/' + self.config['sid'] +
      '/file' +
      '/' + descriptor;
    for (var i = 0; i < input.files.length; i++) {
      files.push(input.files[i]);
    }
    // Send first request with information about files
    let request = new XMLHttpRequest();
    request.open('POST', uri + "/info", true);
    request.send(files.map((f) => `${f.name}/${f.size}`).join('\n'));
  }

  /**
   * @param {string} id
   * @param {string} descriptor
   * @param {string} fileName
   */
  uploadFile(id, descriptor, fileName) {
    let self = this;
    let input = self.els[id];
    let uri = self.config['r'] +
        'bridge' +
        '/' + self.config['sid'] +
        '/file' +
        '/' + descriptor;
    var file = null;

    for (var i = 0; i < input.files.length; i++) {
      if(input.files[i].name == fileName) {
        file = input.files[i];
      }
    }

    if(file) {
      let request = new XMLHttpRequest();
      request.open('POST', uri, true);
      request.send(file);
    } else {
      console.error(`Can't find file with name ${fileName}`);
    }
  }

  downloadFile(descriptor, name) {
    const self = this;
    const a = document.createElement('a');
    document.body.appendChild(a);
    a.style.display = 'none';
    a.download = name;
    a.href = self.config['r'] +
      'bridge' +
      '/' + self.config['sid'] +
      '/file' +
      '/' + descriptor +
      '/' + name;
    a.click();
    document.body.removeChild(a);
  }

  resetForm(id) {
    let element = this.els[id];
    element.reset();
  }

  reloadCss() {
    var links = document.getElementsByTagName("link");
    for (var i = 0; i < links.length; i++) {
      var link = links[i];
      if (link.getAttribute("rel") === "stylesheet")
        link.href = link.href + "?refresh=" + new Date().getMilliseconds();
    }
  }

  /**
   * @param {string} descriptor
   * @param {string} code
   */
  evalJs(descriptor, code) {
    var result;
    var status = 0;
    try {
      result = eval(code);
    } catch (e) {
      console.error(`Error evaluating code ${code}`, e);
      result = e;
      status = 1;
    }

    if (result instanceof Promise) {
      result.then(
        (res) => this.callback(CallbackType.EVALJS_RESPONSE,`${descriptor}:0:${JSON.stringify(res)}`),
        (err) => {
          console.error(`Error evaluating code ${code}`, err);
          this.callback(CallbackType.EVALJS_RESPONSE,`${descriptor}:1:err}`)
        }
      );
    } else {
      var resultString;
      if (status === 1) resultString = result.toString();
      else resultString = JSON.stringify(result);
      this.callback(
        CallbackType.EVALJS_RESPONSE,
        `${descriptor}:${status}:${resultString}`
      );
    }
  }

  extractEventData(descriptor, id, type) {
    let data = this.eventData[eventCounterKey(id, type)];
    let result = {};
    if (!data) {
      this.callback(
        CallbackType.EXTRACT_EVENT_DATA_RESPONSE,
        `${descriptor}:${JSON.stringify(result)}`
      );
      return;
    }
    if (data && data.target) {
      const target = data.target;
      if (typeof target.value === 'string' || typeof target.value === 'number') {
        result['targetValue'] = target.value;
      }
      if (typeof target.checked === 'boolean') {
        result['targetChecked'] = target.checked;
      }
    }
    for (let propertyName in data) {
      let value = data[propertyName];
      switch (typeof value) {
        case 'string':
        case 'number':
        case 'boolean':
          result[propertyName] = value;
          break;
        case 'object':
          if (propertyName === 'detail') {
            result[propertyName] = value;
          }
          break;
        default: // do nothing
      }
    }
    this.callback(
      CallbackType.EXTRACT_EVENT_DATA_RESPONSE,
      `${descriptor}:${JSON.stringify(result)}`
    );
  }
}
