# linux/amd64: the executable jar ships librandomx_linux_x86_64.so and the epoll x86_64
# native. It does not ship a linux aarch64 RandomX library.
FROM --platform=linux/amd64 eclipse-temurin:21-jdk

WORKDIR /opt/xdag
COPY target/xdagj-0.9.0-executable.jar /opt/xdag/xdagj.jar
COPY docker/WalletInit.java /opt/xdag/src/WalletInit.java
RUN javac -proc:none -cp /opt/xdag/xdagj.jar -d /opt/xdag /opt/xdag/src/WalletInit.java \
    && rm -rf /opt/xdag/src
COPY docker/entrypoint.sh /opt/xdag/entrypoint.sh
RUN chmod +x /opt/xdag/entrypoint.sh

ENTRYPOINT ["/opt/xdag/entrypoint.sh"]
