import { Connection } from './connection.js';
import { Bridge, setProtocolDebugEnabled } from './bridge.js';
import { ConnectionLostWidget } from './utils.js';

function showSpoonbillIsNotReadyMessage() {
  console.log("Spoonbill is not ready");
}

window['Spoonbill'] = {
  'setProtocolDebugEnabled': setProtocolDebugEnabled,
  'invokeCallback': () => showSpoonbillIsNotReadyMessage(),
  'swapElementInRegistry': () => showSpoonbillIsNotReadyMessage(),
  'ready': false
};

window.document.addEventListener("DOMContentLoaded", () => {

  let reconnect = true
  let config = window['kfg'];
  let clw = new ConnectionLostWidget(config['clw']);
  let connection = new Connection(
    config['sid'],
    config['r'],
    window.location,
    config
  );

  window['Spoonbill']['disconnect'] = (reconnect = false) => {
    connection.disconnect(reconnect);
  }

  window['Spoonbill']['connect'] = () => connection.connect();

  connection.dispatcher.addEventListener('open', () => {

    let bridge = new Bridge(config, connection);
    let globalObject = window['Spoonbill']

    globalObject['swapElementInRegistry'] = (a, b) => bridge._spoonbill.swapElementInRegistry(a, b);
    globalObject['element'] = (id) => bridge._spoonbill.element(id);
    globalObject['invokeCallback'] = (name, arg) => bridge._spoonbill.invokeCustomCallback(name, arg);

    let closeHandler = (event) => {
      bridge.destroy();
      clw.show();
      globalObject['ready'] = false;
      connection
        .dispatcher
        .removeEventListener('close', closeHandler);
    };
    connection
      .dispatcher
      .addEventListener('close', closeHandler);
  });

  connection.dispatcher.addEventListener('ready', () => {
    clw.hide();
    window['Spoonbill']['ready'] = true;
    window.dispatchEvent(new Event('SpoonbillReady'));
  });

  connection.connect();
});
