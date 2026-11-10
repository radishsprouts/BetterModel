/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kr.toxicity.model.api.manager.ProfileManager
import kr.toxicity.model.api.pack.PackZipper
import kr.toxicity.model.api.profile.ModelProfileSkin
import kr.toxicity.model.api.profile.ModelProfileSupplier
import kr.toxicity.model.profile.DefaultHttpModelProfileSupplier
import kr.toxicity.model.profile.HttpModelProfileSupplier
import kr.toxicity.model.util.PLATFORM
import java.net.URI
import java.util.*

object ProfileManagerImpl : ProfileManager, GlobalManager {

    private val gson = GsonBuilder().create()

    /**
     * The built-in supplier. It is also the one a compatibility hook (e.g. SkinsRestorer)
     * delegates to when it has nothing to provide.
     */
    val defaultSupplier: ModelProfileSupplier by lazy {
        if (PLATFORM.nms().isProxyOnlineMode) DefaultHttpModelProfileSupplier() else HttpModelProfileSupplier()
    }

    @Volatile
    private var supplier: ModelProfileSupplier? = null

    override fun supplier(): ModelProfileSupplier = supplier ?: defaultSupplier

    override fun supplier(supplier: ModelProfileSupplier) {
        this.supplier = supplier
    }

    override fun skin(rawTextures: String): ModelProfileSkin {
        return gson.fromJson(Base64.getDecoder().decode(rawTextures).toString(Charsets.UTF_8), Profile::class.java).run {
            ModelProfileSkin(
                textures.skin?.toURI(),
                textures.cape?.toURI(),
                textures.skin?.metadata?.slim == true,
                rawTextures
            )
        }
    }

    private data class Profile(
        val textures: ProfileTextures
    )

    private data class ProfileTextures(
        @SerializedName("SKIN") val skin: ProfileSkin?,
        @SerializedName("CAPE") val cape: ProfileSkin?,
    )

    private data class ProfileSkin(
        val url: String,
        val metadata: ProfileMetadata?
    ) {
        fun toURI(): URI = URI.create(url)
    }

    private data class ProfileMetadata(
        val model: String
    ) {
        val slim get() = model == "slim"
    }

    override fun start() {
        // A hook that registered its own supplier before this point must not be overwritten.
        if (supplier == null) supplier = defaultSupplier
    }

    override fun reload(pipeline: ReloadPipeline, zipper: PackZipper) {
    }
}
