package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.StringUtils;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;
    private static final int DIRECT_TCP_ACCEPT_TIMEOUT_MS = 5000;

    private static final String SOCKET_NAME_PREFIX = "scrcpy";

    private final LocalSocket videoSocket;
    private final FileDescriptor videoFd;

    private final LocalSocket audioSocket;
    private final FileDescriptor audioFd;

    private final LocalSocket controlSocket;
    private final FileDescriptor controlFd;
    private final ControlChannel controlChannel;

    private final Socket videoTcpSocket;
    private final Socket audioTcpSocket;
    private final Socket controlTcpSocket;

    private final android.os.ParcelFileDescriptor videoTcpPfd;
    private final android.os.ParcelFileDescriptor audioTcpPfd;
    private final android.os.ParcelFileDescriptor controlTcpPfd;

    private DesktopConnection(LocalSocket videoSocket, LocalSocket audioSocket, LocalSocket controlSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.audioSocket = audioSocket;
        this.controlSocket = controlSocket;
        this.videoTcpSocket = null;
        this.audioTcpSocket = null;
        this.controlTcpSocket = null;
        this.videoTcpPfd = null;
        this.audioTcpPfd = null;
        this.controlTcpPfd = null;

        videoFd = videoSocket != null ? videoSocket.getFileDescriptor() : null;
        audioFd = audioSocket != null ? audioSocket.getFileDescriptor() : null;
        controlFd = controlSocket != null ? controlSocket.getFileDescriptor() : null;
        controlChannel = controlSocket != null ? new ControlChannel(controlSocket) : null;
    }

    private DesktopConnection(Socket videoTcpSocket, Socket audioTcpSocket, Socket controlTcpSocket) throws IOException {
        this.videoSocket = null;
        this.audioSocket = null;
        this.controlSocket = null;
        this.videoTcpSocket = videoTcpSocket;
        this.audioTcpSocket = audioTcpSocket;
        this.controlTcpSocket = controlTcpSocket;

        this.videoTcpPfd = videoTcpSocket != null ? android.os.ParcelFileDescriptor.fromSocket(videoTcpSocket) : null;
        this.audioTcpPfd = audioTcpSocket != null ? android.os.ParcelFileDescriptor.fromSocket(audioTcpSocket) : null;
        this.controlTcpPfd = controlTcpSocket != null ? android.os.ParcelFileDescriptor.fromSocket(controlTcpSocket) : null;

        videoFd = getSocketFd(videoTcpSocket, videoTcpPfd);
        audioFd = getSocketFd(audioTcpSocket, audioTcpPfd);
        controlFd = getSocketFd(controlTcpSocket, controlTcpPfd);
        controlChannel = controlTcpSocket != null
                ? new ControlChannel(controlTcpSocket.getInputStream(), controlTcpSocket.getOutputStream())
                : null;
    }

    private static LocalSocket connect(String abstractName) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(abstractName));
        return localSocket;
    }

    private static String getSocketName(int scid) {
        if (scid == -1) {
            // If no SCID is set, use "scrcpy" to simplify using scrcpy-server alone
            return SOCKET_NAME_PREFIX;
        }

        return SOCKET_NAME_PREFIX + String.format("_%08x", scid);
    }

    public static DesktopConnection open(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control, boolean sendDummyByte)
            throws IOException {
        String socketName = getSocketName(scid);

        LocalSocket videoSocket = null;
        LocalSocket audioSocket = null;
        LocalSocket controlSocket = null;
        try {
            if (tunnelForward) {
                try (LocalServerSocket localServerSocket = new LocalServerSocket(socketName)) {
                    if (video) {
                        videoSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            videoSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (audio) {
                        audioSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            audioSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (control) {
                        controlSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            controlSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                }
            } else {
                if (video) {
                    videoSocket = connect(socketName);
                }
                if (audio) {
                    audioSocket = connect(socketName);
                }
                if (control) {
                    controlSocket = connect(socketName);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (videoSocket != null) {
                videoSocket.close();
            }
            if (audioSocket != null) {
                audioSocket.close();
            }
            if (controlSocket != null) {
                controlSocket.close();
            }
            throw e;
        }

        return new DesktopConnection(videoSocket, audioSocket, controlSocket);
    }

    public static DesktopConnection openDirectTcp(int port, boolean video, boolean audio, boolean control, boolean sendDummyByte)
            throws IOException {
        Ln.i("Direct TCP: listening on port " + port + " (timeout " + DIRECT_TCP_ACCEPT_TIMEOUT_MS + "ms)...");
        // 先创建未绑定的 ServerSocket，设置 SO_REUSEADDR 后再绑定，
        // 否则构造函数直接绑定端口会导致 setReuseAddress 无效
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(port));
            serverSocket.setSoTimeout(DIRECT_TCP_ACCEPT_TIMEOUT_MS);

            Socket videoTcpSocket = null;
            Socket audioTcpSocket = null;
            Socket controlTcpSocket = null;
            try {
                if (video) {
                    videoTcpSocket = serverSocket.accept();
                    if (sendDummyByte) {
                        videoTcpSocket.getOutputStream().write(0);
                        sendDummyByte = false;
                    }
                }
                if (audio) {
                    audioTcpSocket = serverSocket.accept();
                    if (sendDummyByte) {
                        audioTcpSocket.getOutputStream().write(0);
                        sendDummyByte = false;
                    }
                }
                if (control) {
                    controlTcpSocket = serverSocket.accept();
                    if (sendDummyByte) {
                        controlTcpSocket.getOutputStream().write(0);
                        sendDummyByte = false;
                    }
                }

                Ln.i("Direct TCP: all connections accepted on port " + port);
                return new DesktopConnection(videoTcpSocket, audioTcpSocket, controlTcpSocket);
            } catch (SocketTimeoutException e) {
                Ln.i("Direct TCP: accept timeout, falling back to LocalSocket");
                closeQuietly(videoTcpSocket, audioTcpSocket, controlTcpSocket);
                return null;
            } catch (IOException | RuntimeException e) {
                closeQuietly(videoTcpSocket, audioTcpSocket, controlTcpSocket);
                throw e;
            }
        } catch (SocketTimeoutException e) {
            Ln.i("Direct TCP: accept timeout on port " + port);
            return null;
        } catch (IOException e) {
            Ln.w("Direct TCP: failed to listen on port " + port + ": " + e.getMessage());
            return null;
        }
    }

    private static void closeQuietly(Socket... sockets) {
        for (Socket socket : sockets) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    Ln.w("Could not close TCP socket: " + e.getMessage());
                }
            }
        }
    }

    private static FileDescriptor getSocketFd(Socket socket, android.os.ParcelFileDescriptor pfd) throws IOException {
        if (socket == null) {
            return null;
        }

        if (pfd != null) {
            return pfd.getFileDescriptor();
        }

        try {
            android.os.ParcelFileDescriptor fallbackPfd = android.os.ParcelFileDescriptor.fromSocket(socket);
            if (fallbackPfd != null) {
                return fallbackPfd.getFileDescriptor();
            }
        } catch (Exception e) {
            Ln.w("ParcelFileDescriptor.fromSocket failed: " + e.getMessage());
        }

        throw new IOException("Cannot get FileDescriptor from TCP socket");
    }

    private LocalSocket getFirstSocket() {
        if (videoSocket != null) {
            return videoSocket;
        }
        if (audioSocket != null) {
            return audioSocket;
        }
        return controlSocket;
    }

    public void shutdown() throws IOException {
        if (videoSocket != null) {
            videoSocket.shutdownInput();
            videoSocket.shutdownOutput();
        }
        if (audioSocket != null) {
            audioSocket.shutdownInput();
            audioSocket.shutdownOutput();
        }
        if (controlSocket != null) {
            controlSocket.shutdownInput();
            controlSocket.shutdownOutput();
        }
        if (videoTcpSocket != null) {
            videoTcpSocket.shutdownInput();
            videoTcpSocket.shutdownOutput();
        }
        if (audioTcpSocket != null) {
            audioTcpSocket.shutdownInput();
            audioTcpSocket.shutdownOutput();
        }
        if (controlTcpSocket != null) {
            controlTcpSocket.shutdownInput();
            controlTcpSocket.shutdownOutput();
        }
    }

    public void close() throws IOException {
        if (videoSocket != null) {
            videoSocket.close();
        }
        if (audioSocket != null) {
            audioSocket.close();
        }
        if (controlSocket != null) {
            controlSocket.close();
        }
        if (videoTcpSocket != null) {
            videoTcpSocket.close();
        }
        if (audioTcpSocket != null) {
            audioTcpSocket.close();
        }
        if (controlTcpSocket != null) {
            controlTcpSocket.close();
        }
        if (videoTcpPfd != null) {
            videoTcpPfd.close();
        }
        if (audioTcpPfd != null) {
            audioTcpPfd.close();
        }
        if (controlTcpPfd != null) {
            controlTcpPfd.close();
        }
    }

    public void sendDeviceMeta(String deviceName) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        // byte[] are always 0-initialized in java, no need to set '\0' explicitly

        FileDescriptor fd;
        LocalSocket firstLocal = getFirstSocket();
        if (firstLocal != null) {
            fd = firstLocal.getFileDescriptor();
        } else {
            fd = videoFd != null ? videoFd : (audioFd != null ? audioFd : controlFd);
        }

        if (fd != null) {
            IO.writeFully(fd, buffer, 0, buffer.length);
        }
    }

    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    public FileDescriptor getAudioFd() {
        return audioFd;
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }
}
