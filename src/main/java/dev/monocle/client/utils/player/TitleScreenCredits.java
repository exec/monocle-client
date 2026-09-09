/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.player;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.addons.AddonManager;
import dev.monocle.client.addons.GithubRepo;
import dev.monocle.client.addons.MonocleAddon;
import dev.monocle.client.gui.GuiThemes;
import dev.monocle.client.gui.screens.CommitsScreen;
import dev.monocle.client.mixininterface.IComponent;
import dev.monocle.client.utils.network.Http;
import dev.monocle.client.utils.network.MonocleExecutor;
import dev.monocle.client.utils.render.MonocleToast;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Items;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static dev.monocle.client.MonocleClient.mc;

public class TitleScreenCredits {
    private static final List<Credit> credits = new ArrayList<>();
    private static final Component UPSTREAM_CREDIT = Component.literal("Based on Meteor Client").withStyle(ChatFormatting.GRAY);

    private TitleScreenCredits() {
    }

    private static void init() {
        // Add addons
        for (MonocleAddon addon : AddonManager.ADDONS) add(addon);

        // Sort by width (Monocle always first)
        credits.sort(Comparator.comparingInt(value -> value.addon == MonocleClient.ADDON ? Integer.MIN_VALUE : -mc.font.width(value.text)));

        // Check for latest commits
        MonocleExecutor.execute(() -> {
            for (Credit credit : credits) {
                if (credit.addon.getRepo() == null || credit.addon.getCommit() == null) continue;

                GithubRepo repo = credit.addon.getRepo();
                Http.Request request = Http.get("https://api.github.com/repos/%s/branches/%s".formatted(repo.getOwnerName(), repo.branch()));
                request.exceptionHandler(e -> MonocleClient.LOG.error("Could not fetch repository information for addon '{}'.", credit.addon.name, e));
                repo.authenticate(request);
                HttpResponse<Response> res = request.sendJsonResponse(Response.class);

                switch (res.statusCode()) {
                    case Http.UNAUTHORIZED -> {
                        String message = "Invalid authentication token for repository '%s'".formatted(repo.getOwnerName());
                        MonocleToast toast = new MonocleToast.Builder("GitHub: Unauthorized").icon(Items.BARRIER).text(message).build();
                        mc.gui.toastManager().addToast(toast);
                        MonocleClient.LOG.warn(message);
                        if (System.getenv("monocle.github.authorization") == null) {
                            MonocleClient.LOG.info("Consider setting an authorization " +
                                "token with the 'monocle.github.authorization' environment variable.");
                            MonocleClient.LOG.info("See: https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens");
                        }
                    }
                    case Http.FORBIDDEN ->
                        MonocleClient.LOG.warn("Could not fetch updates for addon '{}': Rate-limited by GitHub.", credit.addon.name);
                    case Http.NOT_FOUND ->
                        MonocleClient.LOG.warn("Could not fetch updates for addon '{}': GitHub repository '{}' not found.", credit.addon.name, repo.getOwnerName());
                    case Http.SUCCESS -> {
                        if (!credit.addon.getCommit().equals(res.body().commit.sha)) {
                            synchronized (credit.text) {
                                credit.text.append(Component.literal("*").withStyle(ChatFormatting.RED));
                                ((IComponent) ((Component) credit.text)).monocle$invalidateCache(); // ???
                            }
                        }
                    }
                }
            }
        });
    }

    private static void add(MonocleAddon addon) {
        Credit credit = new Credit(addon);

        credit.text.append(Component.literal(addon.name).withStyle(style -> style.withColor(addon.color.getPacked())));
        credit.text.append(Component.literal(" by ").withStyle(ChatFormatting.GRAY));

        for (int i = 0; i < addon.authors.length; i++) {
            if (i > 0) {
                credit.text.append(Component.literal(i == addon.authors.length - 1 ? " & " : ", ").withStyle(ChatFormatting.GRAY));
            }

            credit.text.append(Component.literal(addon.authors[i]).withStyle(ChatFormatting.WHITE));
        }

        credits.add(credit);
    }

    public static void render(GuiGraphicsExtractor graphics) {
        if (credits.isEmpty()) init();

        int y = 3;
        for (Credit credit : credits) {
            synchronized (credit.text) {
                int x = mc.gui.screen().width - 3 - mc.font.width(credit.text);

                graphics.text(mc.font, credit.text, x, y, -1);
            }

            y += mc.font.lineHeight + 2;
        }

        graphics.text(mc.font, UPSTREAM_CREDIT, mc.gui.screen().width - 3 - mc.font.width(UPSTREAM_CREDIT), y, -1);
    }

    public static boolean onClicked(double mouseX, double mouseY) {
        int y = 3;
        for (Credit credit : credits) {
            int width;
            synchronized (credit.text) {
                width = mc.font.width(credit.text);
            }

            int x = mc.gui.screen().width - 3 - width;

            if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + mc.font.lineHeight + 2) {
                if (credit.addon.getRepo() != null && credit.addon.getCommit() != null) {
                    mc.gui.setScreen(new CommitsScreen(GuiThemes.get(), credit.addon));
                    return true;
                }
            }

            y += mc.font.lineHeight + 2;
        }

        return false;
    }

    private static class Credit {
        public final MonocleAddon addon;
        public final MutableComponent text = Component.empty();

        public Credit(MonocleAddon addon) {
            this.addon = addon;
        }
    }

    private static class Response {
        public Commit commit;
    }

    private static class Commit {
        public String sha;
    }
}
