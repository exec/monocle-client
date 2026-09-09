/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.utils.notebot.decoder.SongDecoders;
import net.minecraft.commands.SharedSuggestionProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class NotebotSongArgumentType implements ArgumentType<Path> {
    private static final NotebotSongArgumentType INSTANCE = new NotebotSongArgumentType();

    public static NotebotSongArgumentType create() {
        return INSTANCE;
    }

    private NotebotSongArgumentType() {
    }

    @Override
    public Path parse(StringReader reader) throws CommandSyntaxException {
        final String text = reader.getRemaining();
        reader.setCursor(reader.getTotalLength());
        return MonocleClient.FOLDER.toPath().resolve("notebot/" + text);
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        try (var suggestions = Files.list(MonocleClient.FOLDER.toPath().resolve("notebot"))) {
            return SharedSuggestionProvider.suggest(suggestions
                    .filter(SongDecoders::hasDecoder)
                    .map(path -> path.getFileName().toString()),
                builder
            );
        } catch (IOException _) {
            return Suggestions.empty();
        }
    }
}
