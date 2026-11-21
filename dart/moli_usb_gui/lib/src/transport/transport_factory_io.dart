import 'host_binding.dart';
import 'libserialport_transport.dart';

HostTransportBinding createHostTransportBinding() {
  return HostTransportBinding(
    provider: LibserialportTransportProvider(),
    pollWhileDisconnected: true,
  );
}
