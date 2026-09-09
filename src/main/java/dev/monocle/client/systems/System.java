/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.utils.files.StreamUtils;
import dev.monocle.client.utils.misc.ISerializable;
import net.minecraft.ReportedException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public abstract class System<T> implements ISerializable<T> {
    private final String name;
    private File file;

    protected boolean isFirstInit;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);

    public System(String name) {
        this.name = name;

        if (name != null) {
            this.file = new File(MonocleClient.FOLDER, name + ".nbt");
            this.isFirstInit = !file.exists();
        }
    }

    public void init() {
    }

    public void save(File folder) {
        File file = getFile();
        if (file == null) return;

        CompoundTag tag = toTag();
        if (tag == null) return;

        try {
            File tempFile = File.createTempFile(MonocleClient.MOD_ID, file.getName());
            NbtIo.write(tag, tempFile.toPath());

            if (folder != null) file = new File(folder, file.getName());

            file.getParentFile().mkdirs();

            try {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException _) {
                StreamUtils.copy(tempFile, file);
            }

            tempFile.delete();
        } catch (IOException e) {
            MonocleClient.LOG.error("Error saving {}. Possibly corrupted?", this.name, e);
        }
    }

    public void save() {
        save(null);
    }

    public void load(File folder) {
        File file = getFile();
        if (file == null) return;

        try {
            if (folder != null) file = new File(folder, file.getName());

            if (file.exists()) {
                try {
                    fromTag(NbtIo.read(file.toPath()));
                } catch (ReportedException e) {
                    String backupName = FilenameUtils.removeExtension(file.getName()) + "-" + ZonedDateTime.now().format(DATE_TIME_FORMATTER) + ".backup.nbt";
                    File backup = new File(file.getParentFile(), backupName);

                    try {
                        Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException _) {
                        StreamUtils.copy(file, backup);
                    }

                    MonocleClient.LOG.error("Error loading {}. Possibly corrupted?", this.name, e);
                    MonocleClient.LOG.info("Saved settings backup to '{}'.", backup);
                }
            }
        } catch (IOException e) {
            MonocleClient.LOG.error("Error loading {}. Possibly corrupted?", this.name, e);
        }
    }

    public void load() {
        load(null);
    }

    public File getFile() {
        return file;
    }

    public String getName() {
        return name;
    }

    @Override
    public CompoundTag toTag() {
        return null;
    }

    @Override
    public T fromTag(CompoundTag tag) {
        return null;
    }
}
