package com.meturum.centre.util.input;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_19_R1.block.CraftSign;
import org.bukkit.craftbukkit.v1_19_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

public final class TextInput {

    private final Player owner;
    private final TextInputPacketHandler handler;

    private String[] lines = new String[]{"", "", "", ""}; // The editable lines
    private String[] result = new String[]{"", "", "", ""}; // The result lines (Read-only)

    private boolean isEditing = false;

    private @Nullable UpdateLambda updateLambda = null;

    public TextInput(@NotNull Player owner) {
        this.owner = owner;
        this.handler = new TextInputPacketHandler(this);
    }

    public Player getOwner() {
        return owner;
    }

    public @NotNull String[] getLines() {
        return lines;
    }

    public TextInput setLines(@NotNull final String[] lines) {
        this.lines = lines;

        return this;
    }

    public @NotNull String[] getResult() {
        return result;
    }

    private TextInput setResult(@NotNull final String[] result) {
        this.result = result;

        return this;
    }

    public boolean isEditing() {
        return isEditing;
    }

    private void setEditing(boolean editing) {
        isEditing = editing;
    }

    public void setUpdateLambda(@NotNull UpdateLambda updateLambda) {
        this.updateLambda = updateLambda;
    }

    public void open(boolean force) {
        if(isEditing) return; isEditing = true; // Toggle editing state.

        ServerPlayer player = ((CraftPlayer) owner).getHandle();
        if(!(owner.getOpenInventory() instanceof PlayerInventory))
            owner.closeInventory(); // Close the current inventory if it's not the player's inventory.

        if(player.isImmobile()) return; // If the player is frozen, don't open the sign editor.

        Location location = owner.getLocation();
        BlockPos position = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());

        BlockState originalState = player.level.getBlockState(position); // The current block state at the location.
        BlockState state = Blocks.OAK_SIGN.defaultBlockState();

        player.connection.send(new ClientboundBlockUpdatePacket(position, state)); //  Send a fake sign block update.

        SignBlockEntity entity = new SignBlockEntity(position, state);
        Component[] components = CraftSign.sanitizeLines(lines);

        for (int i = 0; i < components.length; i++) {
            entity.setMessage(i, components[i]);
        }

        player.connection.send(ClientboundBlockEntityDataPacket.create(entity)); // update the sign text
        player.connection.send(new ClientboundOpenSignEditorPacket(position)); // Open that fake sign.
        player.connection.send(new ClientboundBlockUpdatePacket(position, originalState)); // Replace the fake sign with the original block.

        player.connection.connection.channel.pipeline().addBefore("packet_handler", "sign_packet_handler", handler);
    }

    public void open() {
        open(true);
    }

    private void update() {
        if(!isEditing) return; // If the player is not editing, do nothing.
        isEditing = false; // Toggle editing state.

        if(updateLambda != null)
            updateLambda.run(result, lines);

        lines = result;
    }

    public interface UpdateLambda {
        void run(@NotNull String[] newLines, @NotNull String[] oldLines);
    }

    public static class TextInputPacketHandler extends ChannelDuplexHandler {

        private final TextInput input;

        public TextInputPacketHandler(@NotNull TextInput input) {
            this.input = input;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ServerboundSignUpdatePacket packet) {
                String[] lines = packet.getLines();

                input.setResult(lines);
                input.update();

                ctx.pipeline().remove(this);
            }

            super.channelRead(ctx, msg);
        }

    }
}
