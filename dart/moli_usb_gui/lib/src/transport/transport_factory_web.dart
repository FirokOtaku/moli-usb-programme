import 'host_binding.dart';
import 'webserial_transport.dart';

HostTransportBinding createHostTransportBinding() {
  final provider = WebSerialTransportProvider();
  return HostTransportBinding(
    provider: provider,
    pollWhileDisconnected: false,
    requestGrantedPort: provider.requestGrantedPort,
    portEvents: provider.changes,
    grantedPortsHint:
        '浏览器只能列出此前已授权的串口，不能枚举系统上的全部串口。需要新设备时请点击「添加设备」。',
  );
}
