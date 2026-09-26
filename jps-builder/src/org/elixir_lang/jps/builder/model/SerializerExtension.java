package org.elixir_lang.jps.builder.model;

import com.intellij.openapi.diagnostic.Logger;
import org.elixir_lang.jps.builder.compiler_options.Serializer;
import org.elixir_lang.jps.builder.mix.SettingsSerializer;
import org.elixir_lang.jps.builder.sdk_type.Elixir;
import org.elixir_lang.jps.shared.ElixirModuleTypeId;
import org.elixir_lang.jps.shared.ElixirSdkTypeId;
import org.jdom.Attribute;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.JpsDummyElement;
import org.jetbrains.jps.model.JpsElementFactory;
import org.jetbrains.jps.model.serialization.JpsModelSerializerExtension;
import org.jetbrains.jps.model.serialization.JpsProjectExtensionSerializer;
import org.jetbrains.jps.model.serialization.library.JpsSdkPropertiesSerializer;
import org.jetbrains.jps.model.serialization.module.JpsModulePropertiesSerializer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Created by zyuyou on 2015/5/27.
 * <a href="https://github.com/ignatov/intellij-erlang/blob/master/jps-shared/src/org/intellij/erlang/jps/model/JpsErlangModelSerializerExtension.java">...</a>
 */
public class SerializerExtension extends JpsModelSerializerExtension {
    public static final Logger logger = Logger.getInstance(SerializerExtension.class);
    @NotNull
    @Override
    public List<? extends JpsModulePropertiesSerializer<?>> getModulePropertiesSerializers() {
        return Collections.singletonList(new JpsModulePropertiesSerializer<JpsDummyElement>(ModuleType.INSTANCE, ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID, null) {
            @Override
            public JpsDummyElement loadProperties(@Nullable Element componentElement) {
                return JpsElementFactory.getInstance().createDummyElement();
            }
        });
    }

    @NotNull
    @Override
    public List<JpsSdkPropertiesSerializer<SdkProperties>> getSdkPropertiesSerializers() {
        return Collections.singletonList(new JpsSdkPropertiesSerializer<>(ElixirSdkTypeId.ELIXIR_SDK_TYPE_ID, Elixir.INSTANCE) {
            @NotNull
            @Override
            public SdkProperties loadProperties(@Nullable Element propertiesElement) {
                String erlangSdkName = null;

                if (propertiesElement != null) {
                    @Nullable Attribute erlangSdkNameAttribute = propertiesElement.getAttribute("erlang-sdk-name");

                    if (erlangSdkNameAttribute != null) {
                        erlangSdkName = erlangSdkNameAttribute.getValue();
                    }
                }

                return new SdkProperties(erlangSdkName);
            }
        });
    }

    @NotNull
    @Override
    public List<? extends JpsProjectExtensionSerializer> getProjectExtensionSerializers() {
        return Arrays.asList(new SettingsSerializer(), new Serializer());
    }
}
