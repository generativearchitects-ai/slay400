package ai.generativearchitects.slay400.gateway.ibmi;

import ai.generativearchitects.slay400.gateway.config.IbmiSettings;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.AS400SecurityException;
import com.ibm.as400.access.CommandCall;
import com.ibm.as400.access.ErrorCompletingRequestException;
import com.ibm.as400.access.ObjectDoesNotExistException;
import com.ibm.as400.access.ProgramCall;
import com.ibm.as400.access.ProgramParameter;
import com.ibm.as400.access.SecureAS400;
import com.ibm.as400.access.SocketProperties;
import java.beans.PropertyVetoException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLException;

/**
 * Connections through JT400. With {@code tls: true}, the secure host servers are used and the certificate is verified against
 * the JVM's trust store: JT400's default, never switched off here. The library list of the environment is set with
 * {@code CHGLIBL ... CURLIB(*CRTDFT)} on the job of the remote command server, in which every program call of the connection
 * runs. Nothing of the configuration is put in an {@link IbmiFailure}.
 *
 * <p>This is the only class that touches JT400's connection classes; it is tested against a real system in the {@code ibmi}
 * profile.
 */
final class Jt400Connector implements IbmiConnector {

    /** How long opening a connection may take, in milliseconds. */
    static final int LOGIN_TIMEOUT_MS = 10_000;

    private final IbmiSettings settings;

    Jt400Connector(IbmiSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public IbmiConnection open() throws IbmiFailure {
        char[] password = settings.credentials().password();
        AS400 system = settings.tls() ? new SecureAS400(settings.host(), settings.credentials().user(), password)
                : new AS400(settings.host(), settings.credentials().user(), password);
        Arrays.fill(password, '\0');
        try {
            system.setGuiAvailable(false);
            SocketProperties socket = new SocketProperties();
            socket.setLoginTimeout(LOGIN_TIMEOUT_MS);
            system.setSocketProperties(socket);
            system.connectService(AS400.COMMAND);
        } catch (AS400SecurityException e) {
            system.disconnectAllServices();
            throw new IbmiFailure(IbmiFailure.Kind.SIGN_ON_REFUSED, "return code " + e.getReturnCode());
        } catch (UnknownHostException e) {
            system.disconnectAllServices();
            throw new IbmiFailure(IbmiFailure.Kind.UNREACHABLE, "the host name does not resolve");
        } catch (SSLException e) {
            system.disconnectAllServices();
            throw new IbmiFailure(IbmiFailure.Kind.UNREACHABLE, "TLS could not be set up: " + e.getClass().getSimpleName());
        } catch (IOException e) {
            system.disconnectAllServices();
            throw new IbmiFailure(IbmiFailure.Kind.UNREACHABLE, e.getClass().getSimpleName());
        } catch (PropertyVetoException | RuntimeException e) {
            system.disconnectAllServices();
            throw new IbmiFailure(IbmiFailure.Kind.BROKEN, e.getClass().getSimpleName());
        }
        try {
            CommandCall command = new CommandCall(system);
            command.setThreadSafe(false);
            if (!command.run("CHGLIBL LIBL(" + String.join(" ", settings.libraryList()) + ") CURLIB(*CRTDFT)")) {
                String ids = String.join(", ", Arrays.stream(command.getMessageList()).map(AS400Message::getID).toList());
                system.disconnectAllServices();
                throw new IbmiFailure(IbmiFailure.Kind.LIBRARY_LIST, ids.isEmpty() ? "no message" : ids);
            }
        } catch (AS400SecurityException | ErrorCompletingRequestException | IOException | PropertyVetoException | RuntimeException e) {
            system.disconnectAllServices();
            throw new IbmiFailure(IbmiFailure.Kind.LIBRARY_LIST, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            system.disconnectAllServices();
            throw new IbmiFailure(IbmiFailure.Kind.BROKEN, "interrupted");
        }
        return new Jt400Connection(system);
    }

    /** One JT400 connection: the remote command service of an {@link AS400} object. */
    static final class Jt400Connection implements IbmiConnection {

        private final AS400 system;

        Jt400Connection(AS400 system) {
            this.system = system;
        }

        @Override
        public int ccsid() {
            return system.getCcsid();
        }

        @Override
        public boolean alive() {
            try {
                return system.isConnectionAlive(AS400.COMMAND);
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public CallReply call(String programPath, List<ParameterBytes> parameters) throws IbmiFailure {
            ProgramParameter[] list = new ProgramParameter[parameters.size()];
            for (int i = 0; i < list.length; i++) {
                ParameterBytes parameter = parameters.get(i);
                if (parameter.input().isPresent() && parameter.outputLength() > 0) {
                    list[i] = new ProgramParameter(parameter.input().get(), parameter.outputLength());
                } else if (parameter.input().isPresent()) {
                    list[i] = new ProgramParameter(parameter.input().get());
                } else {
                    list[i] = new ProgramParameter(parameter.outputLength());
                }
            }
            ProgramCall call = new ProgramCall(system);
            try {
                call.setProgram(programPath, list);
                call.setThreadSafe(false);
                if (!call.run()) {
                    return new CallReply.Escaped(messages(call));
                }
            } catch (ObjectDoesNotExistException | AS400SecurityException e) {
                List<IbmiMessage> messages = messages(call);
                return new CallReply.Escaped(messages.isEmpty() ? List.of(new IbmiMessage("", e instanceof ObjectDoesNotExistException
                        ? "The program was not found" : "Not authorized")) : messages);
            } catch (ErrorCompletingRequestException | IOException | PropertyVetoException | RuntimeException e) {
                throw new IbmiFailure(IbmiFailure.Kind.BROKEN, e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IbmiFailure(IbmiFailure.Kind.BROKEN, "interrupted");
            }
            List<byte[]> output = new ArrayList<>(list.length);
            for (int i = 0; i < list.length; i++) {
                byte[] data = parameters.get(i).outputLength() > 0 ? list[i].getOutputData() : null;
                output.add(data == null ? new byte[0] : data);
            }
            return new CallReply.Completed(output);
        }

        private static List<IbmiMessage> messages(ProgramCall call) {
            AS400Message[] list = call.getMessageList();
            if (list == null) {
                return List.of();
            }
            return Arrays.stream(list).map(m -> new IbmiMessage(m.getID() == null ? "" : m.getID(), m.getText() == null ? "" : m.getText())).toList();
        }

        @Override
        public void close() {
            try {
                system.disconnectAllServices();
            } catch (RuntimeException e) {
                // Nothing more can be done for a connection that is being closed.
            }
        }
    }
}
