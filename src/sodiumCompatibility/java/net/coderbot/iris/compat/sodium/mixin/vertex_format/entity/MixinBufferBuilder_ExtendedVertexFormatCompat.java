package net.coderbot.iris.compat.sodium.mixin.vertex_format.entity;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.sodium.interop.vanilla.vertex.VanillaVertexFormats;
import net.caffeinemc.sodium.render.vertex.type.VertexType;
import net.coderbot.iris.compat.sodium.impl.vertex_format.entity_xhfp.ExtendedGlyphVertexType;
import net.coderbot.iris.compat.sodium.impl.vertex_format.entity_xhfp.ExtendedQuadVertexType;
import net.coderbot.iris.vertices.IrisVertexFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Apply after Sodium's mixins so that we can mix in to the added method. We do this so that we have the option to
 * use the non-extended vertex format in some cases even if shaders are enabled, without assumptions in the sodium
 * compatibility code getting in the way.
 */
@Mixin(value = BufferBuilder.class, priority = 1010)
public class MixinBufferBuilder_ExtendedVertexFormatCompat {
	@Shadow
	private VertexFormat format;

	@ModifyVariable(method = "createSink(Lnet/caffeinemc/sodium/render/vertex/type/VertexType;)Lnet/caffeinemc/sodium/render/vertex/VertexSink;",
		at = @At("HEAD"), remap = false)
	private VertexType<?> iris$createSink(VertexType<?> type) {
		if (format == IrisVertexFormats.ENTITY) {
			if (type == VanillaVertexFormats.QUADS) {
				return ExtendedQuadVertexType.INSTANCE;
			}
		} else if (format == IrisVertexFormats.TERRAIN) {
			if (type == VanillaVertexFormats.GLYPHS) {
				return ExtendedGlyphVertexType.INSTANCE;
			}
		}

		return type;
	}
}
