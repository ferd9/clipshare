package com.clipshare.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStorageImplTest {

    @TempDir
    Path tempDir;

    private LocalStorageImpl storage;

    @BeforeEach
    void setUp() {
        storage = new LocalStorageImpl(tempDir.toString());
    }

    @Test
    void storesAndResolvesAFile() throws Exception {
        String relative = "abc/original.mp4";
        storage.store(relative, new ByteArrayInputStream("hello".getBytes()));

        Path resolved = storage.resolveLocalPath(relative);
        assertThat(Files.readString(resolved)).isEqualTo("hello");
    }

    @Test
    void createsParentDirectoriesAsNeeded() throws Exception {
        storage.store("a/b/c/file.bin", new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertThat(Files.exists(storage.resolveLocalPath("a/b/c/file.bin"))).isTrue();
    }

    @Test
    void deleteRemovesTheFile() throws Exception {
        storage.store("x/y.bin", new ByteArrayInputStream(new byte[]{1, 2, 3}));
        Path resolved = storage.resolveLocalPath("x/y.bin");
        assertThat(Files.exists(resolved)).isTrue();

        storage.delete("x/y.bin");
        assertThat(Files.exists(resolved)).isFalse();
    }

    @Test
    void deletingAMissingFileIsANoOp() {
        assertThatCode(() -> storage.delete("nope/nope.bin")).doesNotThrowAnyException();
    }

    @Test
    void rejectsPathTraversalOutsideTheBaseDirectory() {
        assertThatThrownBy(() -> storage.resolveLocalPath("../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
