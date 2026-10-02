# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :gl do

  # Private Types

  @typep clamp :: float()

  @typep enum :: non_neg_integer()

  @typep matrix :: (matrix12() | matrix16())

  @typep matrix12 :: {float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}

  @typep matrix16 :: {float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}

  @typep mem :: (binary() | tuple())

  @typep offset :: non_neg_integer()

  # Functions

  @spec accum(op, value) :: :ok when op: enum(), value: float()
  def accum(op, value), do: cast(5084, <<op :: 32-native-unsigned, value :: 32-native-float>>)

  @spec activeShaderProgram(pipeline, program) :: :ok when pipeline: integer(), program: integer()
  def activeShaderProgram(pipeline, program), do: cast(5778, <<pipeline :: 32-native-unsigned, program :: 32-native-unsigned>>)

  @spec activeTexture(texture) :: :ok when texture: enum()
  def activeTexture(texture), do: cast(5358, <<texture :: 32-native-unsigned>>)

  @spec alphaFunc(func, ref) :: :ok when func: enum(), ref: clamp()
  def alphaFunc(func, ref), do: cast(5042, <<func :: 32-native-unsigned, ref :: 32-native-float>>)

  @spec areTexturesResident(textures) :: {(0 | 1), residences :: [(0 | 1)]} when textures: [integer()]
  def areTexturesResident(textures) do
    texturesLen = length(textures)
    call(5275, <<texturesLen :: 32-native-unsigned, (for c <- textures do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + texturesLen, 2) * 32)>>)
  end

  @spec arrayElement(i) :: :ok when i: integer()
  def arrayElement(i), do: cast(5198, <<i :: 32-native-signed>>)

  @spec attachObjectARB(containerObj, obj) :: :ok when containerObj: integer(), obj: integer()
  def attachObjectARB(containerObj, obj), do: cast(5634, <<containerObj :: 64-native-unsigned, obj :: 64-native-unsigned>>)

  @spec attachShader(program, shader) :: :ok when program: integer(), shader: integer()
  def attachShader(program, shader), do: cast(5446, <<program :: 32-native-unsigned, shader :: 32-native-unsigned>>)

  @spec begin(mode) :: :ok when mode: enum()
  def begin(mode), do: cast(5110, <<mode :: 32-native-unsigned>>)

  @spec beginConditionalRender(id, mode) :: :ok when id: integer(), mode: enum()
  def beginConditionalRender(id, mode), do: cast(5540, <<id :: 32-native-unsigned, mode :: 32-native-unsigned>>)

  @spec beginQuery(target, id) :: :ok when target: enum(), id: integer()
  def beginQuery(target, id), do: cast(5426, <<target :: 32-native-unsigned, id :: 32-native-unsigned>>)

  @spec beginQueryIndexed(target, index, id) :: :ok when target: enum(), index: integer(), id: integer()
  def beginQueryIndexed(target, index, id), do: cast(5766, <<target :: 32-native-unsigned, index :: 32-native-unsigned, id :: 32-native-unsigned>>)

  @spec beginTransformFeedback(primitiveMode) :: :ok when primitiveMode: enum()
  def beginTransformFeedback(primitiveMode), do: cast(5533, <<primitiveMode :: 32-native-unsigned>>)

  @spec bindAttribLocation(program, index, name) :: :ok when program: integer(), index: integer(), name: charlist()
  def bindAttribLocation(program, index, name) do
    nameLen = length(name)
    cast(5447, <<program :: 32-native-unsigned, index :: 32-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 1, 8), 8))>>)
  end

  @spec bindAttribLocationARB(programObj, index, name) :: :ok when programObj: integer(), index: integer(), name: charlist()
  def bindAttribLocationARB(programObj, index, name) do
    nameLen = length(name)
    cast(5647, <<programObj :: 64-native-unsigned, index :: 32-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 5, 8), 8))>>)
  end

  @spec bindBuffer(target, buffer) :: :ok when target: enum(), buffer: integer()
  def bindBuffer(target, buffer), do: cast(5431, <<target :: 32-native-unsigned, buffer :: 32-native-unsigned>>)

  @spec bindBufferBase(target, index, buffer) :: :ok when target: enum(), index: integer(), buffer: integer()
  def bindBufferBase(target, index, buffer), do: cast(5536, <<target :: 32-native-unsigned, index :: 32-native-unsigned, buffer :: 32-native-unsigned>>)

  @spec bindBufferRange(target, index, buffer, offset, size) :: :ok when target: enum(), index: integer(), buffer: integer(), offset: integer(), size: integer()
  def bindBufferRange(target, index, buffer, offset, size), do: cast(5535, <<target :: 32-native-unsigned, index :: 32-native-unsigned, buffer :: 32-native-unsigned, 0 :: 32, offset :: 64-native-unsigned, size :: 64-native-unsigned>>)

  @spec bindFragDataLocation(program, color, name) :: :ok when program: integer(), color: integer(), name: charlist()
  def bindFragDataLocation(program, color, name) do
    nameLen = length(name)
    cast(5559, <<program :: 32-native-unsigned, color :: 32-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 1, 8), 8))>>)
  end

  @spec bindFragDataLocationIndexed(program, colorNumber, index, name) :: :ok when program: integer(), colorNumber: integer(), index: integer(), name: charlist()
  def bindFragDataLocationIndexed(program, colorNumber, index, name) do
    nameLen = length(name)
    cast(5708, <<program :: 32-native-unsigned, colorNumber :: 32-native-unsigned, index :: 32-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 5, 8), 8))>>)
  end

  @spec bindFramebuffer(target, framebuffer) :: :ok when target: enum(), framebuffer: integer()
  def bindFramebuffer(target, framebuffer), do: cast(5657, <<target :: 32-native-unsigned, framebuffer :: 32-native-unsigned>>)

  @spec bindImageTexture(unit, texture, level, layered, layer, access, format) :: :ok when unit: integer(), texture: integer(), level: integer(), layered: (0 | 1), layer: integer(), access: enum(), format: enum()
  def bindImageTexture(unit, texture, level, layered, layer, access, format), do: cast(5866, <<unit :: 32-native-unsigned, texture :: 32-native-unsigned, level :: 32-native-signed, layered :: 8-native-unsigned, 0 :: 24, layer :: 32-native-signed, access :: 32-native-unsigned, format :: 32-native-unsigned>>)

  @spec bindProgramARB(target, program) :: :ok when target: enum(), program: integer()
  def bindProgramARB(target, program), do: cast(5610, <<target :: 32-native-unsigned, program :: 32-native-unsigned>>)

  @spec bindProgramPipeline(pipeline) :: :ok when pipeline: integer()
  def bindProgramPipeline(pipeline), do: cast(5780, <<pipeline :: 32-native-unsigned>>)

  @spec bindRenderbuffer(target, renderbuffer) :: :ok when target: enum(), renderbuffer: integer()
  def bindRenderbuffer(target, renderbuffer), do: cast(5651, <<target :: 32-native-unsigned, renderbuffer :: 32-native-unsigned>>)

  @spec bindSampler(unit, sampler) :: :ok when unit: integer(), sampler: integer()
  def bindSampler(unit, sampler), do: cast(5713, <<unit :: 32-native-unsigned, sampler :: 32-native-unsigned>>)

  @spec bindTexture(target, texture) :: :ok when target: enum(), texture: integer()
  def bindTexture(target, texture), do: cast(5273, <<target :: 32-native-unsigned, texture :: 32-native-unsigned>>)

  @spec bindTransformFeedback(target, id) :: :ok when target: enum(), id: integer()
  def bindTransformFeedback(target, id), do: cast(5758, <<target :: 32-native-unsigned, id :: 32-native-unsigned>>)

  @spec bindVertexArray(array) :: :ok when array: integer()
  def bindVertexArray(array), do: cast(5672, <<array :: 32-native-unsigned>>)

  @spec bitmap(width, height, xorig, yorig, xmove, ymove, bitmap) :: :ok when width: integer(), height: integer(), xorig: float(), yorig: float(), xmove: float(), ymove: float(), bitmap: (offset() | mem())
  def bitmap(width, height, xorig, yorig, xmove, ymove, bitmap) when is_integer(bitmap), do: cast(5233, <<width :: 32-native-signed, height :: 32-native-signed, xorig :: 32-native-float, yorig :: 32-native-float, xmove :: 32-native-float, ymove :: 32-native-float, bitmap :: 32-native-unsigned>>)

  def bitmap(width, height, xorig, yorig, xmove, ymove, bitmap) do
    send_bin(bitmap)
    cast(5234, <<width :: 32-native-signed, height :: 32-native-signed, xorig :: 32-native-float, yorig :: 32-native-float, xmove :: 32-native-float, ymove :: 32-native-float>>)
  end

  @spec blendColor(red, green, blue, alpha) :: :ok when red: clamp(), green: clamp(), blue: clamp(), alpha: clamp()
  def blendColor(red, green, blue, alpha), do: cast(5315, <<red :: 32-native-float, green :: 32-native-float, blue :: 32-native-float, alpha :: 32-native-float>>)

  @spec blendEquation(mode) :: :ok when mode: enum()
  def blendEquation(mode), do: cast(5316, <<mode :: 32-native-unsigned>>)

  @spec blendEquationSeparate(modeRGB, modeAlpha) :: :ok when modeRGB: enum(), modeAlpha: enum()
  def blendEquationSeparate(modeRGB, modeAlpha), do: cast(5441, <<modeRGB :: 32-native-unsigned, modeAlpha :: 32-native-unsigned>>)

  @spec blendEquationSeparatei(buf, modeRGB, modeAlpha) :: :ok when buf: integer(), modeRGB: enum(), modeAlpha: enum()
  def blendEquationSeparatei(buf, modeRGB, modeAlpha), do: cast(5589, <<buf :: 32-native-unsigned, modeRGB :: 32-native-unsigned, modeAlpha :: 32-native-unsigned>>)

  @spec blendEquationi(buf, mode) :: :ok when buf: integer(), mode: enum()
  def blendEquationi(buf, mode), do: cast(5588, <<buf :: 32-native-unsigned, mode :: 32-native-unsigned>>)

  @spec blendFunc(sfactor, dfactor) :: :ok when sfactor: enum(), dfactor: enum()
  def blendFunc(sfactor, dfactor), do: cast(5043, <<sfactor :: 32-native-unsigned, dfactor :: 32-native-unsigned>>)

  @spec blendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha) :: :ok when sfactorRGB: enum(), dfactorRGB: enum(), sfactorAlpha: enum(), dfactorAlpha: enum()
  def blendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha), do: cast(5394, <<sfactorRGB :: 32-native-unsigned, dfactorRGB :: 32-native-unsigned, sfactorAlpha :: 32-native-unsigned, dfactorAlpha :: 32-native-unsigned>>)

  @spec blendFuncSeparatei(buf, srcRGB, dstRGB, srcAlpha, dstAlpha) :: :ok when buf: integer(), srcRGB: enum(), dstRGB: enum(), srcAlpha: enum(), dstAlpha: enum()
  def blendFuncSeparatei(buf, srcRGB, dstRGB, srcAlpha, dstAlpha), do: cast(5591, <<buf :: 32-native-unsigned, srcRGB :: 32-native-unsigned, dstRGB :: 32-native-unsigned, srcAlpha :: 32-native-unsigned, dstAlpha :: 32-native-unsigned>>)

  @spec blendFunci(buf, src, dst) :: :ok when buf: integer(), src: enum(), dst: enum()
  def blendFunci(buf, src, dst), do: cast(5590, <<buf :: 32-native-unsigned, src :: 32-native-unsigned, dst :: 32-native-unsigned>>)

  @spec blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter) :: :ok when srcX0: integer(), srcY0: integer(), srcX1: integer(), srcY1: integer(), dstX0: integer(), dstY0: integer(), dstX1: integer(), dstY1: integer(), mask: integer(), filter: enum()
  def blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter), do: cast(5667, <<srcX0 :: 32-native-signed, srcY0 :: 32-native-signed, srcX1 :: 32-native-signed, srcY1 :: 32-native-signed, dstX0 :: 32-native-signed, dstY0 :: 32-native-signed, dstX1 :: 32-native-signed, dstY1 :: 32-native-signed, mask :: 32-native-unsigned, filter :: 32-native-unsigned>>)

  @spec bufferData(target, size, data, usage) :: :ok when target: enum(), size: integer(), data: (offset() | mem()), usage: enum()
  def bufferData(target, size, data, usage) when is_integer(data), do: cast(5435, <<target :: 32-native-unsigned, 0 :: 32, size :: 64-native-unsigned, data :: 32-native-unsigned, usage :: 32-native-unsigned>>)

  def bufferData(target, size, data, usage) do
    send_bin(data)
    cast(5436, <<target :: 32-native-unsigned, 0 :: 32, size :: 64-native-unsigned, usage :: 32-native-unsigned>>)
  end

  @spec bufferSubData(target, offset, size, data) :: :ok when target: enum(), offset: integer(), size: integer(), data: (offset() | mem())
  def bufferSubData(target, offset, size, data) when is_integer(data), do: cast(5437, <<target :: 32-native-unsigned, 0 :: 32, offset :: 64-native-unsigned, size :: 64-native-unsigned, data :: 32-native-unsigned>>)

  def bufferSubData(target, offset, size, data) do
    send_bin(data)
    cast(5438, <<target :: 32-native-unsigned, 0 :: 32, offset :: 64-native-unsigned, size :: 64-native-unsigned>>)
  end

  def call(op, args) do
    port = get(:opengl_port)
    _ = :erlang.port_control(port, op, args)
    rec(op)
  end

  @spec callList(list) :: :ok when list: integer()
  def callList(list), do: cast(5107, <<list :: 32-native-unsigned>>)

  @spec callLists(lists) :: :ok when lists: [integer()]
  def callLists(lists) do
    listsLen = length(lists)
    cast(5108, <<listsLen :: 32-native-unsigned, (for c <- lists do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + listsLen, 2) * 32)>>)
  end

  def cast(op, args) do
    port = get(:opengl_port)
    _ = :erlang.port_control(port, op, args)
    :ok
  end

  @spec checkFramebufferStatus(target) :: enum() when target: enum()
  def checkFramebufferStatus(target), do: call(5660, <<target :: 32-native-unsigned>>)

  @spec clampColor(target, clamp) :: :ok when target: enum(), clamp: enum()
  def clampColor(target, clamp), do: cast(5539, <<target :: 32-native-unsigned, clamp :: 32-native-unsigned>>)

  @spec clear(mask) :: :ok when mask: integer()
  def clear(mask), do: cast(5039, <<mask :: 32-native-unsigned>>)

  @spec clearAccum(red, green, blue, alpha) :: :ok when red: float(), green: float(), blue: float(), alpha: float()
  def clearAccum(red, green, blue, alpha), do: cast(5083, <<red :: 32-native-float, green :: 32-native-float, blue :: 32-native-float, alpha :: 32-native-float>>)

  @spec clearBufferfi(buffer, drawbuffer, depth, stencil) :: :ok when buffer: enum(), drawbuffer: integer(), depth: float(), stencil: integer()
  def clearBufferfi(buffer, drawbuffer, depth, stencil), do: cast(5576, <<buffer :: 32-native-unsigned, drawbuffer :: 32-native-signed, depth :: 32-native-float, stencil :: 32-native-signed>>)

  @spec clearBufferfv(buffer, drawbuffer, value) :: :ok when buffer: enum(), drawbuffer: integer(), value: tuple()
  def clearBufferfv(buffer, drawbuffer, value) do
    cast(5575, <<buffer :: 32-native-unsigned, drawbuffer :: 32-native-signed, size(value) :: 32-native-unsigned, (for c <- tuple_to_list(value) do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(1 + size(value), 2) * 32)>>)
  end

  @spec clearBufferiv(buffer, drawbuffer, value) :: :ok when buffer: enum(), drawbuffer: integer(), value: tuple()
  def clearBufferiv(buffer, drawbuffer, value) do
    cast(5573, <<buffer :: 32-native-unsigned, drawbuffer :: 32-native-signed, size(value) :: 32-native-unsigned, (for c <- tuple_to_list(value) do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + size(value), 2) * 32)>>)
  end

  @spec clearBufferuiv(buffer, drawbuffer, value) :: :ok when buffer: enum(), drawbuffer: integer(), value: tuple()
  def clearBufferuiv(buffer, drawbuffer, value) do
    cast(5574, <<buffer :: 32-native-unsigned, drawbuffer :: 32-native-signed, size(value) :: 32-native-unsigned, (for c <- tuple_to_list(value) do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + size(value), 2) * 32)>>)
  end

  @spec clearColor(red, green, blue, alpha) :: :ok when red: clamp(), green: clamp(), blue: clamp(), alpha: clamp()
  def clearColor(red, green, blue, alpha), do: cast(5038, <<red :: 32-native-float, green :: 32-native-float, blue :: 32-native-float, alpha :: 32-native-float>>)

  @spec clearDepth(depth) :: :ok when depth: clamp()
  def clearDepth(depth), do: cast(5079, <<depth :: 64-native-float>>)

  @spec clearDepthf(d) :: :ok when d: clamp()
  def clearDepthf(d), do: cast(5773, <<d :: 32-native-float>>)

  @spec clearIndex(c) :: :ok when c: float()
  def clearIndex(c), do: cast(5037, <<c :: 32-native-float>>)

  @spec clearStencil(s) :: :ok when s: integer()
  def clearStencil(s), do: cast(5242, <<s :: 32-native-signed>>)

  @spec clientActiveTexture(texture) :: :ok when texture: enum()
  def clientActiveTexture(texture), do: cast(5373, <<texture :: 32-native-unsigned>>)

  @spec clientWaitSync(sync, flags, timeout) :: enum() when sync: integer(), flags: integer(), timeout: integer()
  def clientWaitSync(sync, flags, timeout), do: call(5694, <<sync :: 64-native-unsigned, flags :: 32-native-unsigned, 0 :: 32, timeout :: 64-native-unsigned>>)

  @spec clipPlane(plane, equation) :: :ok when plane: enum(), equation: {float(), float(), float(), float()}
  def clipPlane(plane, {e1, e2, e3, e4}), do: cast(5056, <<plane :: 32-native-unsigned, 0 :: 32, e1 :: 64-native-float, e2 :: 64-native-float, e3 :: 64-native-float, e4 :: 64-native-float>>)

  @spec color3b(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def color3b(red, green, blue), do: cast(5134, <<red :: 8-native-signed, green :: 8-native-signed, blue :: 8-native-signed>>)

  @spec color3bv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def color3bv({red, green, blue}), do: color3b(red, green, blue)

  @spec color3d(red, green, blue) :: :ok when red: float(), green: float(), blue: float()
  def color3d(red, green, blue), do: cast(5135, <<red :: 64-native-float, green :: 64-native-float, blue :: 64-native-float>>)

  @spec color3dv(v) :: :ok when v: {red :: float(), green :: float(), blue :: float()}
  def color3dv({red, green, blue}), do: color3d(red, green, blue)

  @spec color3f(red, green, blue) :: :ok when red: float(), green: float(), blue: float()
  def color3f(red, green, blue), do: cast(5136, <<red :: 32-native-float, green :: 32-native-float, blue :: 32-native-float>>)

  @spec color3fv(v) :: :ok when v: {red :: float(), green :: float(), blue :: float()}
  def color3fv({red, green, blue}), do: color3f(red, green, blue)

  @spec color3i(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def color3i(red, green, blue), do: cast(5137, <<red :: 32-native-signed, green :: 32-native-signed, blue :: 32-native-signed>>)

  @spec color3iv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def color3iv({red, green, blue}), do: color3i(red, green, blue)

  @spec color3s(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def color3s(red, green, blue), do: cast(5138, <<red :: 16-native-signed, green :: 16-native-signed, blue :: 16-native-signed>>)

  @spec color3sv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def color3sv({red, green, blue}), do: color3s(red, green, blue)

  @spec color3ub(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def color3ub(red, green, blue), do: cast(5139, <<red :: 8-native-unsigned, green :: 8-native-unsigned, blue :: 8-native-unsigned>>)

  @spec color3ubv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def color3ubv({red, green, blue}), do: color3ub(red, green, blue)

  @spec color3ui(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def color3ui(red, green, blue), do: cast(5140, <<red :: 32-native-unsigned, green :: 32-native-unsigned, blue :: 32-native-unsigned>>)

  @spec color3uiv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def color3uiv({red, green, blue}), do: color3ui(red, green, blue)

  @spec color3us(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def color3us(red, green, blue), do: cast(5141, <<red :: 16-native-unsigned, green :: 16-native-unsigned, blue :: 16-native-unsigned>>)

  @spec color3usv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def color3usv({red, green, blue}), do: color3us(red, green, blue)

  @spec color4b(red, green, blue, alpha) :: :ok when red: integer(), green: integer(), blue: integer(), alpha: integer()
  def color4b(red, green, blue, alpha), do: cast(5142, <<red :: 8-native-signed, green :: 8-native-signed, blue :: 8-native-signed, alpha :: 8-native-signed>>)

  @spec color4bv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer(), alpha :: integer()}
  def color4bv({red, green, blue, alpha}), do: color4b(red, green, blue, alpha)

  @spec color4d(red, green, blue, alpha) :: :ok when red: float(), green: float(), blue: float(), alpha: float()
  def color4d(red, green, blue, alpha), do: cast(5143, <<red :: 64-native-float, green :: 64-native-float, blue :: 64-native-float, alpha :: 64-native-float>>)

  @spec color4dv(v) :: :ok when v: {red :: float(), green :: float(), blue :: float(), alpha :: float()}
  def color4dv({red, green, blue, alpha}), do: color4d(red, green, blue, alpha)

  @spec color4f(red, green, blue, alpha) :: :ok when red: float(), green: float(), blue: float(), alpha: float()
  def color4f(red, green, blue, alpha), do: cast(5144, <<red :: 32-native-float, green :: 32-native-float, blue :: 32-native-float, alpha :: 32-native-float>>)

  @spec color4fv(v) :: :ok when v: {red :: float(), green :: float(), blue :: float(), alpha :: float()}
  def color4fv({red, green, blue, alpha}), do: color4f(red, green, blue, alpha)

  @spec color4i(red, green, blue, alpha) :: :ok when red: integer(), green: integer(), blue: integer(), alpha: integer()
  def color4i(red, green, blue, alpha), do: cast(5145, <<red :: 32-native-signed, green :: 32-native-signed, blue :: 32-native-signed, alpha :: 32-native-signed>>)

  @spec color4iv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer(), alpha :: integer()}
  def color4iv({red, green, blue, alpha}), do: color4i(red, green, blue, alpha)

  @spec color4s(red, green, blue, alpha) :: :ok when red: integer(), green: integer(), blue: integer(), alpha: integer()
  def color4s(red, green, blue, alpha), do: cast(5146, <<red :: 16-native-signed, green :: 16-native-signed, blue :: 16-native-signed, alpha :: 16-native-signed>>)

  @spec color4sv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer(), alpha :: integer()}
  def color4sv({red, green, blue, alpha}), do: color4s(red, green, blue, alpha)

  @spec color4ub(red, green, blue, alpha) :: :ok when red: integer(), green: integer(), blue: integer(), alpha: integer()
  def color4ub(red, green, blue, alpha), do: cast(5147, <<red :: 8-native-unsigned, green :: 8-native-unsigned, blue :: 8-native-unsigned, alpha :: 8-native-unsigned>>)

  @spec color4ubv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer(), alpha :: integer()}
  def color4ubv({red, green, blue, alpha}), do: color4ub(red, green, blue, alpha)

  @spec color4ui(red, green, blue, alpha) :: :ok when red: integer(), green: integer(), blue: integer(), alpha: integer()
  def color4ui(red, green, blue, alpha), do: cast(5148, <<red :: 32-native-unsigned, green :: 32-native-unsigned, blue :: 32-native-unsigned, alpha :: 32-native-unsigned>>)

  @spec color4uiv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer(), alpha :: integer()}
  def color4uiv({red, green, blue, alpha}), do: color4ui(red, green, blue, alpha)

  @spec color4us(red, green, blue, alpha) :: :ok when red: integer(), green: integer(), blue: integer(), alpha: integer()
  def color4us(red, green, blue, alpha), do: cast(5149, <<red :: 16-native-unsigned, green :: 16-native-unsigned, blue :: 16-native-unsigned, alpha :: 16-native-unsigned>>)

  @spec color4usv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer(), alpha :: integer()}
  def color4usv({red, green, blue, alpha}), do: color4us(red, green, blue, alpha)

  @spec colorMask(red, green, blue, alpha) :: :ok when red: (0 | 1), green: (0 | 1), blue: (0 | 1), alpha: (0 | 1)
  def colorMask(red, green, blue, alpha), do: cast(5041, <<red :: 8-native-unsigned, green :: 8-native-unsigned, blue :: 8-native-unsigned, alpha :: 8-native-unsigned>>)

  @spec colorMaski(index, r, g, b, a) :: :ok when index: integer(), r: (0 | 1), g: (0 | 1), b: (0 | 1), a: (0 | 1)
  def colorMaski(index, r, g, b, a), do: cast(5527, <<index :: 32-native-unsigned, r :: 8-native-unsigned, g :: 8-native-unsigned, b :: 8-native-unsigned, a :: 8-native-unsigned>>)

  @spec colorMaterial(face, mode) :: :ok when face: enum(), mode: enum()
  def colorMaterial(face, mode), do: cast(5221, <<face :: 32-native-unsigned, mode :: 32-native-unsigned>>)

  @spec colorPointer(size, type, stride, ptr) :: :ok when size: integer(), type: enum(), stride: integer(), ptr: (offset() | mem())
  def colorPointer(size, type, stride, ptr) when is_integer(ptr), do: cast(5190, <<size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed, ptr :: 32-native-unsigned>>)

  def colorPointer(size, type, stride, ptr) do
    send_bin(ptr)
    cast(5191, <<size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed>>)
  end

  @spec colorSubTable(target, start, count, format, type, data) :: :ok when target: enum(), start: integer(), count: integer(), format: enum(), type: enum(), data: (offset() | mem())
  def colorSubTable(target, start, count, format, type, data) when is_integer(data), do: cast(5332, <<target :: 32-native-unsigned, start :: 32-native-signed, count :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, data :: 32-native-unsigned>>)

  def colorSubTable(target, start, count, format, type, data) do
    send_bin(data)
    cast(5333, <<target :: 32-native-unsigned, start :: 32-native-signed, count :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec colorTable(target, internalformat, width, format, type, table) :: :ok when target: enum(), internalformat: enum(), width: integer(), format: enum(), type: enum(), table: (offset() | mem())
  def colorTable(target, internalformat, width, format, type, table) when is_integer(table), do: cast(5324, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, width :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, table :: 32-native-unsigned>>)

  def colorTable(target, internalformat, width, format, type, table) do
    send_bin(table)
    cast(5325, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, width :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec colorTableParameterfv(target, pname, params) :: :ok when target: enum(), pname: enum(), params: {float(), float(), float(), float()}
  def colorTableParameterfv(target, pname, {p1, p2, p3, p4}), do: cast(5326, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, p1 :: 32-native-float, p2 :: 32-native-float, p3 :: 32-native-float, p4 :: 32-native-float>>)

  @spec colorTableParameteriv(target, pname, params) :: :ok when target: enum(), pname: enum(), params: {integer(), integer(), integer(), integer()}
  def colorTableParameteriv(target, pname, {p1, p2, p3, p4}), do: cast(5327, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, p1 :: 32-native-signed, p2 :: 32-native-signed, p3 :: 32-native-signed, p4 :: 32-native-signed>>)

  @spec compileShader(shader) :: :ok when shader: integer()
  def compileShader(shader), do: cast(5448, <<shader :: 32-native-unsigned>>)

  @spec compileShaderARB(shaderObj) :: :ok when shaderObj: integer()
  def compileShaderARB(shaderObj), do: cast(5632, <<shaderObj :: 64-native-unsigned>>)

  @spec compileShaderIncludeARB(shader, path) :: :ok when shader: integer(), path: iolist()
  def compileShaderIncludeARB(shader, path) do
    pathTemp = list_to_binary((for str <- path do
      [str, 0]
    end))
    pathLen = length(path)
    cast(5704, <<shader :: 32-native-unsigned, pathLen :: 32-native-unsigned, size(pathTemp) :: 32-native-unsigned, pathTemp :: binary, 0 :: size(rem(8 - rem(size(pathTemp) + 0, 8), 8))>>)
  end

  @spec compressedTexImage1D(target, level, internalformat, width, border, imageSize, data) :: :ok when target: enum(), level: integer(), internalformat: enum(), width: integer(), border: integer(), imageSize: integer(), data: (offset() | mem())
  def compressedTexImage1D(target, level, internalformat, width, border, imageSize, data) when is_integer(data), do: cast(5364, <<target :: 32-native-unsigned, level :: 32-native-signed, internalformat :: 32-native-unsigned, width :: 32-native-signed, border :: 32-native-signed, imageSize :: 32-native-signed, data :: 32-native-unsigned>>)

  def compressedTexImage1D(target, level, internalformat, width, border, imageSize, data) do
    send_bin(data)
    cast(5365, <<target :: 32-native-unsigned, level :: 32-native-signed, internalformat :: 32-native-unsigned, width :: 32-native-signed, border :: 32-native-signed, imageSize :: 32-native-signed>>)
  end

  @spec compressedTexImage2D(target, level, internalformat, width, height, border, imageSize, data) :: :ok when target: enum(), level: integer(), internalformat: enum(), width: integer(), height: integer(), border: integer(), imageSize: integer(), data: (offset() | mem())
  def compressedTexImage2D(target, level, internalformat, width, height, border, imageSize, data) when is_integer(data), do: cast(5362, <<target :: 32-native-unsigned, level :: 32-native-signed, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed, border :: 32-native-signed, imageSize :: 32-native-signed, data :: 32-native-unsigned>>)

  def compressedTexImage2D(target, level, internalformat, width, height, border, imageSize, data) do
    send_bin(data)
    cast(5363, <<target :: 32-native-unsigned, level :: 32-native-signed, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed, border :: 32-native-signed, imageSize :: 32-native-signed>>)
  end

  @spec compressedTexImage3D(target, level, internalformat, width, height, depth, border, imageSize, data) :: :ok when target: enum(), level: integer(), internalformat: enum(), width: integer(), height: integer(), depth: integer(), border: integer(), imageSize: integer(), data: (offset() | mem())
  def compressedTexImage3D(target, level, internalformat, width, height, depth, border, imageSize, data) when is_integer(data), do: cast(5360, <<target :: 32-native-unsigned, level :: 32-native-signed, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed, depth :: 32-native-signed, border :: 32-native-signed, imageSize :: 32-native-signed, data :: 32-native-unsigned>>)

  def compressedTexImage3D(target, level, internalformat, width, height, depth, border, imageSize, data) do
    send_bin(data)
    cast(5361, <<target :: 32-native-unsigned, level :: 32-native-signed, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed, depth :: 32-native-signed, border :: 32-native-signed, imageSize :: 32-native-signed>>)
  end

  @spec compressedTexSubImage1D(target, level, xoffset, width, format, imageSize, data) :: :ok when target: enum(), level: integer(), xoffset: integer(), width: integer(), format: enum(), imageSize: integer(), data: (offset() | mem())
  def compressedTexSubImage1D(target, level, xoffset, width, format, imageSize, data) when is_integer(data), do: cast(5370, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, width :: 32-native-signed, format :: 32-native-unsigned, imageSize :: 32-native-signed, data :: 32-native-unsigned>>)

  def compressedTexSubImage1D(target, level, xoffset, width, format, imageSize, data) do
    send_bin(data)
    cast(5371, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, width :: 32-native-signed, format :: 32-native-unsigned, imageSize :: 32-native-signed>>)
  end

  @spec compressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, imageSize, data) :: :ok when target: enum(), level: integer(), xoffset: integer(), yoffset: integer(), width: integer(), height: integer(), format: enum(), imageSize: integer(), data: (offset() | mem())
  def compressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, imageSize, data) when is_integer(data), do: cast(5368, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, yoffset :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, format :: 32-native-unsigned, imageSize :: 32-native-signed, data :: 32-native-unsigned>>)

  def compressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, imageSize, data) do
    send_bin(data)
    cast(5369, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, yoffset :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, format :: 32-native-unsigned, imageSize :: 32-native-signed>>)
  end

  @spec compressedTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, imageSize, data) :: :ok when target: enum(), level: integer(), xoffset: integer(), yoffset: integer(), zoffset: integer(), width: integer(), height: integer(), depth: integer(), format: enum(), imageSize: integer(), data: (offset() | mem())
  def compressedTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, imageSize, data) when is_integer(data), do: cast(5366, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, yoffset :: 32-native-signed, zoffset :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, depth :: 32-native-signed, format :: 32-native-unsigned, imageSize :: 32-native-signed, data :: 32-native-unsigned>>)

  def compressedTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, imageSize, data) do
    send_bin(data)
    cast(5367, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, yoffset :: 32-native-signed, zoffset :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, depth :: 32-native-signed, format :: 32-native-unsigned, imageSize :: 32-native-signed>>)
  end

  @spec convolutionFilter1D(target, internalformat, width, format, type, image) :: :ok when target: enum(), internalformat: enum(), width: integer(), format: enum(), type: enum(), image: (offset() | mem())
  def convolutionFilter1D(target, internalformat, width, format, type, image) when is_integer(image), do: cast(5335, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, width :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, image :: 32-native-unsigned>>)

  def convolutionFilter1D(target, internalformat, width, format, type, image) do
    send_bin(image)
    cast(5336, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, width :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec convolutionFilter2D(target, internalformat, width, height, format, type, image) :: :ok when target: enum(), internalformat: enum(), width: integer(), height: integer(), format: enum(), type: enum(), image: (offset() | mem())
  def convolutionFilter2D(target, internalformat, width, height, format, type, image) when is_integer(image), do: cast(5337, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, image :: 32-native-unsigned>>)

  def convolutionFilter2D(target, internalformat, width, height, format, type, image) do
    send_bin(image)
    cast(5338, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec convolutionParameterf(target, pname, params) :: :ok when target: enum(), pname: enum(), params: tuple()
  def convolutionParameterf(target, pname, params) do
    cast(5339, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec convolutionParameterfv(target :: enum(), pname :: enum(), params) :: :ok when params: {params :: tuple()}
  def convolutionParameterfv(target, pname, {params}), do: convolutionParameterf(target, pname, params)

  @spec convolutionParameteri(target, pname, params) :: :ok when target: enum(), pname: enum(), params: tuple()
  def convolutionParameteri(target, pname, params) do
    cast(5340, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec convolutionParameteriv(target :: enum(), pname :: enum(), params) :: :ok when params: {params :: tuple()}
  def convolutionParameteriv(target, pname, {params}), do: convolutionParameteri(target, pname, params)

  @spec copyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size) :: :ok when readTarget: enum(), writeTarget: enum(), readOffset: integer(), writeOffset: integer(), size: integer()
  def copyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size), do: cast(5683, <<readTarget :: 32-native-unsigned, writeTarget :: 32-native-unsigned, readOffset :: 64-native-unsigned, writeOffset :: 64-native-unsigned, size :: 64-native-unsigned>>)

  @spec copyColorSubTable(target, start, x, y, width) :: :ok when target: enum(), start: integer(), x: integer(), y: integer(), width: integer()
  def copyColorSubTable(target, start, x, y, width), do: cast(5334, <<target :: 32-native-unsigned, start :: 32-native-signed, x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed>>)

  @spec copyColorTable(target, internalformat, x, y, width) :: :ok when target: enum(), internalformat: enum(), x: integer(), y: integer(), width: integer()
  def copyColorTable(target, internalformat, x, y, width), do: cast(5328, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed>>)

  @spec copyConvolutionFilter1D(target, internalformat, x, y, width) :: :ok when target: enum(), internalformat: enum(), x: integer(), y: integer(), width: integer()
  def copyConvolutionFilter1D(target, internalformat, x, y, width), do: cast(5341, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed>>)

  @spec copyConvolutionFilter2D(target, internalformat, x, y, width, height) :: :ok when target: enum(), internalformat: enum(), x: integer(), y: integer(), width: integer(), height: integer()
  def copyConvolutionFilter2D(target, internalformat, x, y, width, height), do: cast(5342, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed>>)

  @spec copyPixels(x, y, width, height, type) :: :ok when x: integer(), y: integer(), width: integer(), height: integer(), type: enum()
  def copyPixels(x, y, width, height, type), do: cast(5238, <<x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, type :: 32-native-unsigned>>)

  @spec copyTexImage1D(target, level, internalformat, x, y, width, border) :: :ok when target: enum(), level: integer(), internalformat: enum(), x: integer(), y: integer(), width: integer(), border: integer()
  def copyTexImage1D(target, level, internalformat, x, y, width, border), do: cast(5281, <<target :: 32-native-unsigned, level :: 32-native-signed, internalformat :: 32-native-unsigned, x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed, border :: 32-native-signed>>)

  @spec copyTexImage2D(target, level, internalformat, x, y, width, height, border) :: :ok when target: enum(), level: integer(), internalformat: enum(), x: integer(), y: integer(), width: integer(), height: integer(), border: integer()
  def copyTexImage2D(target, level, internalformat, x, y, width, height, border), do: cast(5282, <<target :: 32-native-unsigned, level :: 32-native-signed, internalformat :: 32-native-unsigned, x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, border :: 32-native-signed>>)

  @spec copyTexSubImage1D(target, level, xoffset, x, y, width) :: :ok when target: enum(), level: integer(), xoffset: integer(), x: integer(), y: integer(), width: integer()
  def copyTexSubImage1D(target, level, xoffset, x, y, width), do: cast(5283, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed>>)

  @spec copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height) :: :ok when target: enum(), level: integer(), xoffset: integer(), yoffset: integer(), x: integer(), y: integer(), width: integer(), height: integer()
  def copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height), do: cast(5284, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, yoffset :: 32-native-signed, x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed>>)

  @spec copyTexSubImage3D(target, level, xoffset, yoffset, zoffset, x, y, width, height) :: :ok when target: enum(), level: integer(), xoffset: integer(), yoffset: integer(), zoffset: integer(), x: integer(), y: integer(), width: integer(), height: integer()
  def copyTexSubImage3D(target, level, xoffset, yoffset, zoffset, x, y, width, height), do: cast(5323, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, yoffset :: 32-native-signed, zoffset :: 32-native-signed, x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed>>)

  @spec createProgram() :: integer()
  def createProgram(), do: call(5449, <<>>)

  @spec createProgramObjectARB() :: integer()
  def createProgramObjectARB(), do: call(5633, <<>>)

  @spec createShader(type) :: integer() when type: enum()
  def createShader(type), do: call(5450, <<type :: 32-native-unsigned>>)

  @spec createShaderObjectARB(shaderType) :: integer() when shaderType: enum()
  def createShaderObjectARB(shaderType), do: call(5630, <<shaderType :: 32-native-unsigned>>)

  @spec createShaderProgramv(type, strings) :: integer() when type: enum(), strings: iolist()
  def createShaderProgramv(type, strings) do
    stringsTemp = list_to_binary((for str <- strings do
      [str, 0]
    end))
    stringsLen = length(strings)
    call(5779, <<type :: 32-native-unsigned, stringsLen :: 32-native-unsigned, size(stringsTemp) :: 32-native-unsigned, stringsTemp :: binary, 0 :: size(rem(8 - rem(size(stringsTemp) + 0, 8), 8))>>)
  end

  @spec cullFace(mode) :: :ok when mode: enum()
  def cullFace(mode), do: cast(5045, <<mode :: 32-native-unsigned>>)

  @spec currentPaletteMatrixARB(index) :: :ok when index: integer()
  def currentPaletteMatrixARB(index), do: cast(5605, <<index :: 32-native-signed>>)

  @spec debugMessageControlARB(source, type, severity, ids, enabled) :: :ok when source: enum(), type: enum(), severity: enum(), ids: [integer()], enabled: (0 | 1)
  def debugMessageControlARB(source, type, severity, ids, enabled) do
    idsLen = length(ids)
    cast(5854, <<source :: 32-native-unsigned, type :: 32-native-unsigned, severity :: 32-native-unsigned, idsLen :: 32-native-unsigned, (for c <- ids do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(idsLen, 2) * 32), enabled :: 8-native-unsigned>>)
  end

  @spec debugMessageInsertARB(source, type, id, severity, buf) :: :ok when source: enum(), type: enum(), id: integer(), severity: enum(), buf: charlist()
  def debugMessageInsertARB(source, type, id, severity, buf) do
    bufLen = length(buf)
    cast(5855, <<source :: 32-native-unsigned, type :: 32-native-unsigned, id :: 32-native-unsigned, severity :: 32-native-unsigned, list_to_binary([buf, 0]) :: binary, 0 :: size(rem(8 - rem(bufLen + 1, 8), 8))>>)
  end

  @spec deleteBuffers(buffers) :: :ok when buffers: [integer()]
  def deleteBuffers(buffers) do
    buffersLen = length(buffers)
    cast(5432, <<buffersLen :: 32-native-unsigned, (for c <- buffers do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + buffersLen, 2) * 32)>>)
  end

  @spec deleteFramebuffers(framebuffers) :: :ok when framebuffers: [integer()]
  def deleteFramebuffers(framebuffers) do
    framebuffersLen = length(framebuffers)
    cast(5658, <<framebuffersLen :: 32-native-unsigned, (for c <- framebuffers do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + framebuffersLen, 2) * 32)>>)
  end

  @spec deleteLists(list, range) :: :ok when list: integer(), range: integer()
  def deleteLists(list, range), do: cast(5103, <<list :: 32-native-unsigned, range :: 32-native-signed>>)

  @spec deleteNamedStringARB(name) :: :ok when name: charlist()
  def deleteNamedStringARB(name) do
    nameLen = length(name)
    cast(5703, <<list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 1, 8), 8))>>)
  end

  @spec deleteObjectARB(obj) :: :ok when obj: integer()
  def deleteObjectARB(obj), do: cast(5627, <<obj :: 64-native-unsigned>>)

  @spec deleteProgram(program) :: :ok when program: integer()
  def deleteProgram(program), do: cast(5451, <<program :: 32-native-unsigned>>)

  @spec deleteProgramPipelines(pipelines) :: :ok when pipelines: [integer()]
  def deleteProgramPipelines(pipelines) do
    pipelinesLen = length(pipelines)
    cast(5781, <<pipelinesLen :: 32-native-unsigned, (for c <- pipelines do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + pipelinesLen, 2) * 32)>>)
  end

  @spec deleteProgramsARB(programs) :: :ok when programs: [integer()]
  def deleteProgramsARB(programs) do
    programsLen = length(programs)
    cast(5611, <<programsLen :: 32-native-unsigned, (for c <- programs do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + programsLen, 2) * 32)>>)
  end

  @spec deleteQueries(ids) :: :ok when ids: [integer()]
  def deleteQueries(ids) do
    idsLen = length(ids)
    cast(5424, <<idsLen :: 32-native-unsigned, (for c <- ids do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + idsLen, 2) * 32)>>)
  end

  @spec deleteRenderbuffers(renderbuffers) :: :ok when renderbuffers: [integer()]
  def deleteRenderbuffers(renderbuffers) do
    renderbuffersLen = length(renderbuffers)
    cast(5652, <<renderbuffersLen :: 32-native-unsigned, (for c <- renderbuffers do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + renderbuffersLen, 2) * 32)>>)
  end

  @spec deleteSamplers(samplers) :: :ok when samplers: [integer()]
  def deleteSamplers(samplers) do
    samplersLen = length(samplers)
    cast(5711, <<samplersLen :: 32-native-unsigned, (for c <- samplers do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + samplersLen, 2) * 32)>>)
  end

  @spec deleteShader(shader) :: :ok when shader: integer()
  def deleteShader(shader), do: cast(5452, <<shader :: 32-native-unsigned>>)

  @spec deleteSync(sync) :: :ok when sync: integer()
  def deleteSync(sync), do: cast(5693, <<sync :: 64-native-unsigned>>)

  @spec deleteTextures(textures) :: :ok when textures: [integer()]
  def deleteTextures(textures) do
    texturesLen = length(textures)
    cast(5272, <<texturesLen :: 32-native-unsigned, (for c <- textures do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + texturesLen, 2) * 32)>>)
  end

  @spec deleteTransformFeedbacks(ids) :: :ok when ids: [integer()]
  def deleteTransformFeedbacks(ids) do
    idsLen = length(ids)
    cast(5759, <<idsLen :: 32-native-unsigned, (for c <- ids do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + idsLen, 2) * 32)>>)
  end

  @spec deleteVertexArrays(arrays) :: :ok when arrays: [integer()]
  def deleteVertexArrays(arrays) do
    arraysLen = length(arrays)
    cast(5673, <<arraysLen :: 32-native-unsigned, (for c <- arrays do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + arraysLen, 2) * 32)>>)
  end

  @spec depthBoundsEXT(zmin, zmax) :: :ok when zmin: clamp(), zmax: clamp()
  def depthBoundsEXT(zmin, zmax), do: cast(5871, <<zmin :: 64-native-float, zmax :: 64-native-float>>)

  @spec depthFunc(func) :: :ok when func: enum()
  def depthFunc(func), do: cast(5080, <<func :: 32-native-unsigned>>)

  @spec depthMask(flag) :: :ok when flag: (0 | 1)
  def depthMask(flag), do: cast(5081, <<flag :: 8-native-unsigned>>)

  @spec depthRange(near_val, far_val) :: :ok when near_val: clamp(), far_val: clamp()
  def depthRange(near_val, far_val), do: cast(5082, <<near_val :: 64-native-float, far_val :: 64-native-float>>)

  @spec depthRangeArrayv(first, v) :: :ok when first: integer(), v: [{clamp(), clamp()}]
  def depthRangeArrayv(first, v) do
    vLen = length(v)
    cast(5850, <<first :: 32-native-unsigned, 0 :: 32, vLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2} <- v do
      <<v1 :: 64-native-float, v2 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec depthRangeIndexed(index, n, f) :: :ok when index: integer(), n: clamp(), f: clamp()
  def depthRangeIndexed(index, n, f), do: cast(5851, <<index :: 32-native-unsigned, 0 :: 32, n :: 64-native-float, f :: 64-native-float>>)

  @spec depthRangef(n, f) :: :ok when n: clamp(), f: clamp()
  def depthRangef(n, f), do: cast(5772, <<n :: 32-native-float, f :: 32-native-float>>)

  @spec detachObjectARB(containerObj, attachedObj) :: :ok when containerObj: integer(), attachedObj: integer()
  def detachObjectARB(containerObj, attachedObj), do: cast(5629, <<containerObj :: 64-native-unsigned, attachedObj :: 64-native-unsigned>>)

  @spec detachShader(program, shader) :: :ok when program: integer(), shader: integer()
  def detachShader(program, shader), do: cast(5453, <<program :: 32-native-unsigned, shader :: 32-native-unsigned>>)

  @spec disable(cap) :: :ok when cap: enum()
  def disable(cap), do: cast(5061, <<cap :: 32-native-unsigned>>)

  @spec disableClientState(cap) :: :ok when cap: enum()
  def disableClientState(cap), do: cast(5064, <<cap :: 32-native-unsigned>>)

  @spec disableVertexAttribArray(index) :: :ok when index: integer()
  def disableVertexAttribArray(index), do: cast(5454, <<index :: 32-native-unsigned>>)

  @spec disablei(target, index) :: :ok when target: enum(), index: integer()
  def disablei(target, index), do: cast(5531, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec drawArrays(mode, first, count) :: :ok when mode: enum(), first: integer(), count: integer()
  def drawArrays(mode, first, count), do: cast(5199, <<mode :: 32-native-unsigned, first :: 32-native-signed, count :: 32-native-signed>>)

  @spec drawArraysIndirect(mode, indirect) :: :ok when mode: enum(), indirect: (offset() | mem())
  def drawArraysIndirect(mode, indirect) when is_integer(indirect), do: cast(5727, <<mode :: 32-native-unsigned, indirect :: 32-native-unsigned>>)

  def drawArraysIndirect(mode, indirect) do
    send_bin(indirect)
    cast(5728, <<mode :: 32-native-unsigned>>)
  end

  @spec drawArraysInstanced(mode, first, count, primcount) :: :ok when mode: enum(), first: integer(), count: integer(), primcount: integer()
  def drawArraysInstanced(mode, first, count, primcount), do: cast(5578, <<mode :: 32-native-unsigned, first :: 32-native-signed, count :: 32-native-signed, primcount :: 32-native-signed>>)

  @spec drawArraysInstancedBaseInstance(mode, first, count, primcount, baseinstance) :: :ok when mode: enum(), first: integer(), count: integer(), primcount: integer(), baseinstance: integer()
  def drawArraysInstancedBaseInstance(mode, first, count, primcount, baseinstance), do: cast(5858, <<mode :: 32-native-unsigned, first :: 32-native-signed, count :: 32-native-signed, primcount :: 32-native-signed, baseinstance :: 32-native-unsigned>>)

  @spec drawBuffer(mode) :: :ok when mode: enum()
  def drawBuffer(mode), do: cast(5058, <<mode :: 32-native-unsigned>>)

  @spec drawBuffers(bufs) :: :ok when bufs: [enum()]
  def drawBuffers(bufs) do
    bufsLen = length(bufs)
    cast(5442, <<bufsLen :: 32-native-unsigned, (for c <- bufs do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + bufsLen, 2) * 32)>>)
  end

  @spec drawElements(mode, count, type, indices) :: :ok when mode: enum(), count: integer(), type: enum(), indices: (offset() | mem())
  def drawElements(mode, count, type, indices) when is_integer(indices), do: cast(5200, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, indices :: 32-native-unsigned>>)

  def drawElements(mode, count, type, indices) do
    send_bin(indices)
    cast(5201, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned>>)
  end

  @spec drawElementsBaseVertex(mode, count, type, indices, basevertex) :: :ok when mode: enum(), count: integer(), type: enum(), indices: (offset() | mem()), basevertex: integer()
  def drawElementsBaseVertex(mode, count, type, indices, basevertex) when is_integer(indices), do: cast(5684, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, indices :: 32-native-unsigned, basevertex :: 32-native-signed>>)

  def drawElementsBaseVertex(mode, count, type, indices, basevertex) do
    send_bin(indices)
    cast(5685, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, basevertex :: 32-native-signed>>)
  end

  @spec drawElementsIndirect(mode, type, indirect) :: :ok when mode: enum(), type: enum(), indirect: (offset() | mem())
  def drawElementsIndirect(mode, type, indirect) when is_integer(indirect), do: cast(5729, <<mode :: 32-native-unsigned, type :: 32-native-unsigned, indirect :: 32-native-unsigned>>)

  def drawElementsIndirect(mode, type, indirect) do
    send_bin(indirect)
    cast(5730, <<mode :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec drawElementsInstanced(mode, count, type, indices, primcount) :: :ok when mode: enum(), count: integer(), type: enum(), indices: (offset() | mem()), primcount: integer()
  def drawElementsInstanced(mode, count, type, indices, primcount) when is_integer(indices), do: cast(5579, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, indices :: 32-native-unsigned, primcount :: 32-native-signed>>)

  def drawElementsInstanced(mode, count, type, indices, primcount) do
    send_bin(indices)
    cast(5580, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, primcount :: 32-native-signed>>)
  end

  @spec drawElementsInstancedBaseInstance(mode, count, type, indices, primcount, baseinstance) :: :ok when mode: enum(), count: integer(), type: enum(), indices: (offset() | mem()), primcount: integer(), baseinstance: integer()
  def drawElementsInstancedBaseInstance(mode, count, type, indices, primcount, baseinstance) when is_integer(indices), do: cast(5859, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, indices :: 32-native-unsigned, primcount :: 32-native-signed, baseinstance :: 32-native-unsigned>>)

  def drawElementsInstancedBaseInstance(mode, count, type, indices, primcount, baseinstance) do
    send_bin(indices)
    cast(5860, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, primcount :: 32-native-signed, baseinstance :: 32-native-unsigned>>)
  end

  @spec drawElementsInstancedBaseVertex(mode, count, type, indices, primcount, basevertex) :: :ok when mode: enum(), count: integer(), type: enum(), indices: (offset() | mem()), primcount: integer(), basevertex: integer()
  def drawElementsInstancedBaseVertex(mode, count, type, indices, primcount, basevertex) when is_integer(indices), do: cast(5688, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, indices :: 32-native-unsigned, primcount :: 32-native-signed, basevertex :: 32-native-signed>>)

  def drawElementsInstancedBaseVertex(mode, count, type, indices, primcount, basevertex) do
    send_bin(indices)
    cast(5689, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, primcount :: 32-native-signed, basevertex :: 32-native-signed>>)
  end

  @spec drawElementsInstancedBaseVertexBaseInstance(mode, count, type, indices, primcount, basevertex, baseinstance) :: :ok when mode: enum(), count: integer(), type: enum(), indices: (offset() | mem()), primcount: integer(), basevertex: integer(), baseinstance: integer()
  def drawElementsInstancedBaseVertexBaseInstance(mode, count, type, indices, primcount, basevertex, baseinstance) when is_integer(indices), do: cast(5861, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, indices :: 32-native-unsigned, primcount :: 32-native-signed, basevertex :: 32-native-signed, baseinstance :: 32-native-unsigned>>)

  def drawElementsInstancedBaseVertexBaseInstance(mode, count, type, indices, primcount, basevertex, baseinstance) do
    send_bin(indices)
    cast(5862, <<mode :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, primcount :: 32-native-signed, basevertex :: 32-native-signed, baseinstance :: 32-native-unsigned>>)
  end

  @spec drawPixels(width, height, format, type, pixels) :: :ok when width: integer(), height: integer(), format: enum(), type: enum(), pixels: (offset() | mem())
  def drawPixels(width, height, format, type, pixels) when is_integer(pixels), do: cast(5236, <<width :: 32-native-signed, height :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, pixels :: 32-native-unsigned>>)

  def drawPixels(width, height, format, type, pixels) do
    send_bin(pixels)
    cast(5237, <<width :: 32-native-signed, height :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec drawRangeElements(mode, start, erlangVariableEnd, count, type, indices) :: :ok when mode: enum(), start: integer(), erlangVariableEnd: integer(), count: integer(), type: enum(), indices: (offset() | mem())
  def drawRangeElements(mode, start, erlangVariableEnd, count, type, indices) when is_integer(indices), do: cast(5317, <<mode :: 32-native-unsigned, start :: 32-native-unsigned, erlangVariableEnd :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, indices :: 32-native-unsigned>>)

  def drawRangeElements(mode, start, erlangVariableEnd, count, type, indices) do
    send_bin(indices)
    cast(5318, <<mode :: 32-native-unsigned, start :: 32-native-unsigned, erlangVariableEnd :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned>>)
  end

  @spec drawRangeElementsBaseVertex(mode, start, erlangVariableEnd, count, type, indices, basevertex) :: :ok when mode: enum(), start: integer(), erlangVariableEnd: integer(), count: integer(), type: enum(), indices: (offset() | mem()), basevertex: integer()
  def drawRangeElementsBaseVertex(mode, start, erlangVariableEnd, count, type, indices, basevertex) when is_integer(indices), do: cast(5686, <<mode :: 32-native-unsigned, start :: 32-native-unsigned, erlangVariableEnd :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, indices :: 32-native-unsigned, basevertex :: 32-native-signed>>)

  def drawRangeElementsBaseVertex(mode, start, erlangVariableEnd, count, type, indices, basevertex) do
    send_bin(indices)
    cast(5687, <<mode :: 32-native-unsigned, start :: 32-native-unsigned, erlangVariableEnd :: 32-native-unsigned, count :: 32-native-signed, type :: 32-native-unsigned, basevertex :: 32-native-signed>>)
  end

  @spec drawTransformFeedback(mode, id) :: :ok when mode: enum(), id: integer()
  def drawTransformFeedback(mode, id), do: cast(5764, <<mode :: 32-native-unsigned, id :: 32-native-unsigned>>)

  @spec drawTransformFeedbackInstanced(mode, id, primcount) :: :ok when mode: enum(), id: integer(), primcount: integer()
  def drawTransformFeedbackInstanced(mode, id, primcount), do: cast(5863, <<mode :: 32-native-unsigned, id :: 32-native-unsigned, primcount :: 32-native-signed>>)

  @spec drawTransformFeedbackStream(mode, id, stream) :: :ok when mode: enum(), id: integer(), stream: integer()
  def drawTransformFeedbackStream(mode, id, stream), do: cast(5765, <<mode :: 32-native-unsigned, id :: 32-native-unsigned, stream :: 32-native-unsigned>>)

  @spec drawTransformFeedbackStreamInstanced(mode, id, stream, primcount) :: :ok when mode: enum(), id: integer(), stream: integer(), primcount: integer()
  def drawTransformFeedbackStreamInstanced(mode, id, stream, primcount), do: cast(5864, <<mode :: 32-native-unsigned, id :: 32-native-unsigned, stream :: 32-native-unsigned, primcount :: 32-native-signed>>)

  @spec edgeFlag(flag) :: :ok when flag: (0 | 1)
  def edgeFlag(flag), do: cast(5054, <<flag :: 8-native-unsigned>>)

  @spec edgeFlagPointer(stride, ptr) :: :ok when stride: integer(), ptr: (offset() | mem())
  def edgeFlagPointer(stride, ptr) when is_integer(ptr), do: cast(5196, <<stride :: 32-native-signed, ptr :: 32-native-unsigned>>)

  def edgeFlagPointer(stride, ptr) do
    send_bin(ptr)
    cast(5197, <<stride :: 32-native-signed>>)
  end

  @spec edgeFlagv(flag) :: :ok when flag: {flag :: (0 | 1)}
  def edgeFlagv({flag}), do: edgeFlag(flag)

  @spec enable(cap) :: :ok when cap: enum()
  def enable(cap), do: cast(5060, <<cap :: 32-native-unsigned>>)

  @spec enableClientState(cap) :: :ok when cap: enum()
  def enableClientState(cap), do: cast(5063, <<cap :: 32-native-unsigned>>)

  @spec enableVertexAttribArray(index) :: :ok when index: integer()
  def enableVertexAttribArray(index), do: cast(5455, <<index :: 32-native-unsigned>>)

  @spec enablei(target, index) :: :ok when target: enum(), index: integer()
  def enablei(target, index), do: cast(5530, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec unquote(:end)() :: :ok
  def unquote(:end)(), do: cast(5111, <<>>)

  @spec endConditionalRender() :: :ok
  def endConditionalRender(), do: cast(5541, <<>>)

  @spec endList() :: :ok
  def endList(), do: cast(5106, <<>>)

  @spec endQuery(target) :: :ok when target: enum()
  def endQuery(target), do: cast(5427, <<target :: 32-native-unsigned>>)

  @spec endQueryIndexed(target, index) :: :ok when target: enum(), index: integer()
  def endQueryIndexed(target, index), do: cast(5767, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec endTransformFeedback() :: :ok
  def endTransformFeedback(), do: cast(5534, <<>>)

  @spec evalCoord1d(u) :: :ok when u: float()
  def evalCoord1d(u), do: cast(5292, <<u :: 64-native-float>>)

  @spec evalCoord1dv(u) :: :ok when u: {u :: float()}
  def evalCoord1dv({u}), do: evalCoord1d(u)

  @spec evalCoord1f(u) :: :ok when u: float()
  def evalCoord1f(u), do: cast(5293, <<u :: 32-native-float>>)

  @spec evalCoord1fv(u) :: :ok when u: {u :: float()}
  def evalCoord1fv({u}), do: evalCoord1f(u)

  @spec evalCoord2d(u, v) :: :ok when u: float(), v: float()
  def evalCoord2d(u, v), do: cast(5294, <<u :: 64-native-float, v :: 64-native-float>>)

  @spec evalCoord2dv(u) :: :ok when u: {u :: float(), v :: float()}
  def evalCoord2dv({u, v}), do: evalCoord2d(u, v)

  @spec evalCoord2f(u, v) :: :ok when u: float(), v: float()
  def evalCoord2f(u, v), do: cast(5295, <<u :: 32-native-float, v :: 32-native-float>>)

  @spec evalCoord2fv(u) :: :ok when u: {u :: float(), v :: float()}
  def evalCoord2fv({u, v}), do: evalCoord2f(u, v)

  @spec evalMesh1(mode, i1, i2) :: :ok when mode: enum(), i1: integer(), i2: integer()
  def evalMesh1(mode, i1, i2), do: cast(5302, <<mode :: 32-native-unsigned, i1 :: 32-native-signed, i2 :: 32-native-signed>>)

  @spec evalMesh2(mode, i1, i2, j1, j2) :: :ok when mode: enum(), i1: integer(), i2: integer(), j1: integer(), j2: integer()
  def evalMesh2(mode, i1, i2, j1, j2), do: cast(5303, <<mode :: 32-native-unsigned, i1 :: 32-native-signed, i2 :: 32-native-signed, j1 :: 32-native-signed, j2 :: 32-native-signed>>)

  @spec evalPoint1(i) :: :ok when i: integer()
  def evalPoint1(i), do: cast(5300, <<i :: 32-native-signed>>)

  @spec evalPoint2(i, j) :: :ok when i: integer(), j: integer()
  def evalPoint2(i, j), do: cast(5301, <<i :: 32-native-signed, j :: 32-native-signed>>)

  @spec feedbackBuffer(size, type, buffer) :: :ok when size: integer(), type: enum(), buffer: mem()
  def feedbackBuffer(size, type, buffer) do
    send_bin(buffer)
    call(5308, <<size :: 32-native-signed, type :: 32-native-unsigned>>)
  end

  @spec fenceSync(condition, flags) :: integer() when condition: enum(), flags: integer()
  def fenceSync(condition, flags), do: call(5691, <<condition :: 32-native-unsigned, flags :: 32-native-unsigned>>)

  @spec finish() :: :ok
  def finish(), do: cast(5076, <<>>)

  @spec flush() :: :ok
  def flush(), do: cast(5077, <<>>)

  @spec flushMappedBufferRange(target, offset, length) :: :ok when target: enum(), offset: integer(), length: integer()
  def flushMappedBufferRange(target, offset, length), do: cast(5671, <<target :: 32-native-unsigned, 0 :: 32, offset :: 64-native-unsigned, length :: 64-native-unsigned>>)

  @spec fogCoordPointer(type, stride, pointer) :: :ok when type: enum(), stride: integer(), pointer: (offset() | mem())
  def fogCoordPointer(type, stride, pointer) when is_integer(pointer), do: cast(5403, <<type :: 32-native-unsigned, stride :: 32-native-signed, pointer :: 32-native-unsigned>>)

  def fogCoordPointer(type, stride, pointer) do
    send_bin(pointer)
    cast(5404, <<type :: 32-native-unsigned, stride :: 32-native-signed>>)
  end

  @spec fogCoordd(coord) :: :ok when coord: float()
  def fogCoordd(coord), do: cast(5402, <<coord :: 64-native-float>>)

  @spec fogCoorddv(coord) :: :ok when coord: {coord :: float()}
  def fogCoorddv({coord}), do: fogCoordd(coord)

  @spec fogCoordf(coord) :: :ok when coord: float()
  def fogCoordf(coord), do: cast(5401, <<coord :: 32-native-float>>)

  @spec fogCoordfv(coord) :: :ok when coord: {coord :: float()}
  def fogCoordfv({coord}), do: fogCoordf(coord)

  @spec fogf(pname, param) :: :ok when pname: enum(), param: float()
  def fogf(pname, param), do: cast(5304, <<pname :: 32-native-unsigned, param :: 32-native-float>>)

  @spec fogfv(pname, params) :: :ok when pname: enum(), params: tuple()
  def fogfv(pname, params) do
    cast(5306, <<pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(0 + size(params), 2) * 32)>>)
  end

  @spec fogi(pname, param) :: :ok when pname: enum(), param: integer()
  def fogi(pname, param), do: cast(5305, <<pname :: 32-native-unsigned, param :: 32-native-signed>>)

  @spec fogiv(pname, params) :: :ok when pname: enum(), params: tuple()
  def fogiv(pname, params) do
    cast(5307, <<pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(0 + size(params), 2) * 32)>>)
  end

  @spec framebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer) :: :ok when target: enum(), attachment: enum(), renderbuffertarget: enum(), renderbuffer: integer()
  def framebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer), do: cast(5664, <<target :: 32-native-unsigned, attachment :: 32-native-unsigned, renderbuffertarget :: 32-native-unsigned, renderbuffer :: 32-native-unsigned>>)

  @spec framebufferTexture(target, attachment, texture, level) :: :ok when target: enum(), attachment: enum(), texture: integer(), level: integer()
  def framebufferTexture(target, attachment, texture, level), do: cast(5585, <<target :: 32-native-unsigned, attachment :: 32-native-unsigned, texture :: 32-native-unsigned, level :: 32-native-signed>>)

  @spec framebufferTexture1D(target, attachment, textarget, texture, level) :: :ok when target: enum(), attachment: enum(), textarget: enum(), texture: integer(), level: integer()
  def framebufferTexture1D(target, attachment, textarget, texture, level), do: cast(5661, <<target :: 32-native-unsigned, attachment :: 32-native-unsigned, textarget :: 32-native-unsigned, texture :: 32-native-unsigned, level :: 32-native-signed>>)

  @spec framebufferTexture2D(target, attachment, textarget, texture, level) :: :ok when target: enum(), attachment: enum(), textarget: enum(), texture: integer(), level: integer()
  def framebufferTexture2D(target, attachment, textarget, texture, level), do: cast(5662, <<target :: 32-native-unsigned, attachment :: 32-native-unsigned, textarget :: 32-native-unsigned, texture :: 32-native-unsigned, level :: 32-native-signed>>)

  @spec framebufferTexture3D(target, attachment, textarget, texture, level, zoffset) :: :ok when target: enum(), attachment: enum(), textarget: enum(), texture: integer(), level: integer(), zoffset: integer()
  def framebufferTexture3D(target, attachment, textarget, texture, level, zoffset), do: cast(5663, <<target :: 32-native-unsigned, attachment :: 32-native-unsigned, textarget :: 32-native-unsigned, texture :: 32-native-unsigned, level :: 32-native-signed, zoffset :: 32-native-signed>>)

  @spec framebufferTextureFaceARB(target, attachment, texture, level, face) :: :ok when target: enum(), attachment: enum(), texture: integer(), level: integer(), face: enum()
  def framebufferTextureFaceARB(target, attachment, texture, level, face), do: cast(5670, <<target :: 32-native-unsigned, attachment :: 32-native-unsigned, texture :: 32-native-unsigned, level :: 32-native-signed, face :: 32-native-unsigned>>)

  @spec framebufferTextureLayer(target, attachment, texture, level, layer) :: :ok when target: enum(), attachment: enum(), texture: integer(), level: integer(), layer: integer()
  def framebufferTextureLayer(target, attachment, texture, level, layer), do: cast(5669, <<target :: 32-native-unsigned, attachment :: 32-native-unsigned, texture :: 32-native-unsigned, level :: 32-native-signed, layer :: 32-native-signed>>)

  @spec frontFace(mode) :: :ok when mode: enum()
  def frontFace(mode), do: cast(5046, <<mode :: 32-native-unsigned>>)

  @spec frustum(left, right, bottom, top, near_val, far_val) :: :ok when left: float(), right: float(), bottom: float(), top: float(), near_val: float(), far_val: float()
  def frustum(left, right, bottom, top, near_val, far_val), do: cast(5087, <<left :: 64-native-float, right :: 64-native-float, bottom :: 64-native-float, top :: 64-native-float, near_val :: 64-native-float, far_val :: 64-native-float>>)

  @spec genBuffers(n) :: [integer()] when n: integer()
  def genBuffers(n), do: call(5433, <<n :: 32-native-signed>>)

  @spec genFramebuffers(n) :: [integer()] when n: integer()
  def genFramebuffers(n), do: call(5659, <<n :: 32-native-signed>>)

  @spec genLists(range) :: integer() when range: integer()
  def genLists(range), do: call(5104, <<range :: 32-native-signed>>)

  @spec genProgramPipelines(n) :: [integer()] when n: integer()
  def genProgramPipelines(n), do: call(5782, <<n :: 32-native-signed>>)

  @spec genProgramsARB(n) :: [integer()] when n: integer()
  def genProgramsARB(n), do: call(5612, <<n :: 32-native-signed>>)

  @spec genQueries(n) :: [integer()] when n: integer()
  def genQueries(n), do: call(5423, <<n :: 32-native-signed>>)

  @spec genRenderbuffers(n) :: [integer()] when n: integer()
  def genRenderbuffers(n), do: call(5653, <<n :: 32-native-signed>>)

  @spec genSamplers(count) :: [integer()] when count: integer()
  def genSamplers(count), do: call(5710, <<count :: 32-native-signed>>)

  @spec genTextures(n) :: [integer()] when n: integer()
  def genTextures(n), do: call(5271, <<n :: 32-native-signed>>)

  @spec genTransformFeedbacks(n) :: [integer()] when n: integer()
  def genTransformFeedbacks(n), do: call(5760, <<n :: 32-native-signed>>)

  @spec genVertexArrays(n) :: [integer()] when n: integer()
  def genVertexArrays(n), do: call(5674, <<n :: 32-native-signed>>)

  @spec generateMipmap(target) :: :ok when target: enum()
  def generateMipmap(target), do: cast(5666, <<target :: 32-native-unsigned>>)

  @spec getActiveAttrib(program, index, bufSize) :: {size :: integer(), type :: enum(), name :: charlist()} when program: integer(), index: integer(), bufSize: integer()
  def getActiveAttrib(program, index, bufSize), do: call(5456, <<program :: 32-native-unsigned, index :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getActiveAttribARB(programObj, index, maxLength) :: {size :: integer(), type :: enum(), name :: charlist()} when programObj: integer(), index: integer(), maxLength: integer()
  def getActiveAttribARB(programObj, index, maxLength), do: call(5648, <<programObj :: 64-native-unsigned, index :: 32-native-unsigned, maxLength :: 32-native-signed>>)

  @spec getActiveSubroutineName(program, shadertype, index, bufsize) :: charlist() when program: integer(), shadertype: enum(), index: integer(), bufsize: integer()
  def getActiveSubroutineName(program, shadertype, index, bufsize), do: call(5752, <<program :: 32-native-unsigned, shadertype :: 32-native-unsigned, index :: 32-native-unsigned, bufsize :: 32-native-signed>>)

  @spec getActiveSubroutineUniformName(program, shadertype, index, bufsize) :: charlist() when program: integer(), shadertype: enum(), index: integer(), bufsize: integer()
  def getActiveSubroutineUniformName(program, shadertype, index, bufsize), do: call(5751, <<program :: 32-native-unsigned, shadertype :: 32-native-unsigned, index :: 32-native-unsigned, bufsize :: 32-native-signed>>)

  @spec getActiveUniform(program, index, bufSize) :: {size :: integer(), type :: enum(), name :: charlist()} when program: integer(), index: integer(), bufSize: integer()
  def getActiveUniform(program, index, bufSize), do: call(5457, <<program :: 32-native-unsigned, index :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getActiveUniformARB(programObj, index, maxLength) :: {size :: integer(), type :: enum(), name :: charlist()} when programObj: integer(), index: integer(), maxLength: integer()
  def getActiveUniformARB(programObj, index, maxLength), do: call(5643, <<programObj :: 64-native-unsigned, index :: 32-native-unsigned, maxLength :: 32-native-signed>>)

  @spec getActiveUniformBlockName(program, uniformBlockIndex, bufSize) :: charlist() when program: integer(), uniformBlockIndex: integer(), bufSize: integer()
  def getActiveUniformBlockName(program, uniformBlockIndex, bufSize), do: call(5681, <<program :: 32-native-unsigned, uniformBlockIndex :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getActiveUniformBlockiv(program, uniformBlockIndex, pname, params) :: :ok when program: integer(), uniformBlockIndex: integer(), pname: enum(), params: mem()
  def getActiveUniformBlockiv(program, uniformBlockIndex, pname, params) do
    send_bin(params)
    call(5680, <<program :: 32-native-unsigned, uniformBlockIndex :: 32-native-unsigned, pname :: 32-native-unsigned>>)
  end

  @spec getActiveUniformName(program, uniformIndex, bufSize) :: charlist() when program: integer(), uniformIndex: integer(), bufSize: integer()
  def getActiveUniformName(program, uniformIndex, bufSize), do: call(5678, <<program :: 32-native-unsigned, uniformIndex :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getActiveUniformsiv(program, uniformIndices, pname) :: [integer()] when program: integer(), uniformIndices: [integer()], pname: enum()
  def getActiveUniformsiv(program, uniformIndices, pname) do
    uniformIndicesLen = length(uniformIndices)
    call(5677, <<program :: 32-native-unsigned, uniformIndicesLen :: 32-native-unsigned, (for c <- uniformIndices do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(uniformIndicesLen, 2) * 32), pname :: 32-native-unsigned>>)
  end

  @spec getAttachedObjectsARB(containerObj, maxCount) :: [integer()] when containerObj: integer(), maxCount: integer()
  def getAttachedObjectsARB(containerObj, maxCount), do: call(5641, <<containerObj :: 64-native-unsigned, maxCount :: 32-native-signed>>)

  @spec getAttachedShaders(program, maxCount) :: [integer()] when program: integer(), maxCount: integer()
  def getAttachedShaders(program, maxCount), do: call(5458, <<program :: 32-native-unsigned, maxCount :: 32-native-signed>>)

  @spec getAttribLocation(program, name) :: integer() when program: integer(), name: charlist()
  def getAttribLocation(program, name) do
    nameLen = length(name)
    call(5459, <<program :: 32-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 5, 8), 8))>>)
  end

  @spec getAttribLocationARB(programObj, name) :: integer() when programObj: integer(), name: charlist()
  def getAttribLocationARB(programObj, name) do
    nameLen = length(name)
    call(5649, <<programObj :: 64-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 1, 8), 8))>>)
  end

  @spec getBooleani_v(target, index) :: [(0 | 1)] when target: enum(), index: integer()
  def getBooleani_v(target, index), do: call(5528, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec getBooleanv(pname) :: [(0 | 1)] when pname: enum()
  def getBooleanv(pname), do: call(5065, <<pname :: 32-native-unsigned>>)

  @spec getBufferParameteri64v(target, pname) :: [integer()] when target: enum(), pname: enum()
  def getBufferParameteri64v(target, pname), do: call(5584, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getBufferParameteriv(target, pname) :: integer() when target: enum(), pname: enum()
  def getBufferParameteriv(target, pname), do: call(5440, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getBufferParameterivARB(target, pname) :: [integer()] when target: enum(), pname: enum()
  def getBufferParameterivARB(target, pname), do: call(5626, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getBufferSubData(target, offset, size, data) :: :ok when target: enum(), offset: integer(), size: integer(), data: mem()
  def getBufferSubData(target, offset, size, data) do
    send_bin(data)
    call(5439, <<target :: 32-native-unsigned, 0 :: 32, offset :: 64-native-unsigned, size :: 64-native-unsigned>>)
  end

  @spec getClipPlane(plane) :: {float(), float(), float(), float()} when plane: enum()
  def getClipPlane(plane), do: call(5057, <<plane :: 32-native-unsigned>>)

  @spec getColorTable(target, format, type, table) :: :ok when target: enum(), format: enum(), type: enum(), table: mem()
  def getColorTable(target, format, type, table) do
    send_bin(table)
    call(5329, <<target :: 32-native-unsigned, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec getColorTableParameterfv(target, pname) :: {float(), float(), float(), float()} when target: enum(), pname: enum()
  def getColorTableParameterfv(target, pname), do: call(5330, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getColorTableParameteriv(target, pname) :: {integer(), integer(), integer(), integer()} when target: enum(), pname: enum()
  def getColorTableParameteriv(target, pname), do: call(5331, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getCompressedTexImage(target, lod, img) :: :ok when target: enum(), lod: integer(), img: mem()
  def getCompressedTexImage(target, lod, img) do
    send_bin(img)
    call(5372, <<target :: 32-native-unsigned, lod :: 32-native-signed>>)
  end

  @spec getConvolutionFilter(target, format, type, image) :: :ok when target: enum(), format: enum(), type: enum(), image: mem()
  def getConvolutionFilter(target, format, type, image) do
    send_bin(image)
    call(5343, <<target :: 32-native-unsigned, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec getConvolutionParameterfv(target, pname) :: {float(), float(), float(), float()} when target: enum(), pname: enum()
  def getConvolutionParameterfv(target, pname), do: call(5344, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getConvolutionParameteriv(target, pname) :: {integer(), integer(), integer(), integer()} when target: enum(), pname: enum()
  def getConvolutionParameteriv(target, pname), do: call(5345, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getDebugMessageLogARB(count, bufsize) :: {integer(), sources :: [enum()], types :: [enum()], ids :: [integer()], severities :: [enum()], messageLog :: [charlist()]} when count: integer(), bufsize: integer()
  def getDebugMessageLogARB(count, bufsize), do: call(5856, <<count :: 32-native-unsigned, bufsize :: 32-native-signed>>)

  @spec getDoublei_v(target, index) :: [float()] when target: enum(), index: integer()
  def getDoublei_v(target, index), do: call(5853, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec getDoublev(pname) :: [float()] when pname: enum()
  def getDoublev(pname), do: call(5066, <<pname :: 32-native-unsigned>>)

  @spec getError() :: enum()
  def getError(), do: call(5074, <<>>)

  @spec getFloati_v(target, index) :: [float()] when target: enum(), index: integer()
  def getFloati_v(target, index), do: call(5852, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec getFloatv(pname) :: [float()] when pname: enum()
  def getFloatv(pname), do: call(5067, <<pname :: 32-native-unsigned>>)

  @spec getFragDataIndex(program, name) :: integer() when program: integer(), name: charlist()
  def getFragDataIndex(program, name) do
    nameLen = length(name)
    call(5709, <<program :: 32-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 5, 8), 8))>>)
  end

  @spec getFragDataLocation(program, name) :: integer() when program: integer(), name: charlist()
  def getFragDataLocation(program, name) do
    nameLen = length(name)
    call(5560, <<program :: 32-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 5, 8), 8))>>)
  end

  @spec getFramebufferAttachmentParameteriv(target, attachment, pname) :: integer() when target: enum(), attachment: enum(), pname: enum()
  def getFramebufferAttachmentParameteriv(target, attachment, pname), do: call(5665, <<target :: 32-native-unsigned, attachment :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getGraphicsResetStatusARB() :: enum()
  def getGraphicsResetStatusARB(), do: call(5857, <<>>)

  @spec getHandleARB(pname) :: integer() when pname: enum()
  def getHandleARB(pname), do: call(5628, <<pname :: 32-native-unsigned>>)

  @spec getHistogram(target, reset, format, type, values) :: :ok when target: enum(), reset: (0 | 1), format: enum(), type: enum(), values: mem()
  def getHistogram(target, reset, format, type, values) do
    send_bin(values)
    call(5348, <<target :: 32-native-unsigned, reset :: 8-native-unsigned, 0 :: 24, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec getHistogramParameterfv(target, pname) :: {float()} when target: enum(), pname: enum()
  def getHistogramParameterfv(target, pname), do: call(5349, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getHistogramParameteriv(target, pname) :: {integer()} when target: enum(), pname: enum()
  def getHistogramParameteriv(target, pname), do: call(5350, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getInfoLogARB(obj, maxLength) :: charlist() when obj: integer(), maxLength: integer()
  def getInfoLogARB(obj, maxLength), do: call(5640, <<obj :: 64-native-unsigned, maxLength :: 32-native-signed>>)

  @spec getInteger64i_v(target, index) :: [integer()] when target: enum(), index: integer()
  def getInteger64i_v(target, index), do: call(5583, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec getInteger64v(pname) :: [integer()] when pname: enum()
  def getInteger64v(pname), do: call(5696, <<pname :: 32-native-unsigned>>)

  @spec getIntegeri_v(target, index) :: [integer()] when target: enum(), index: integer()
  def getIntegeri_v(target, index), do: call(5529, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec getIntegerv(pname) :: [integer()] when pname: enum()
  def getIntegerv(pname), do: call(5068, <<pname :: 32-native-unsigned>>)

  @spec getInternalformativ(target, internalformat, pname, bufSize) :: [integer()] when target: enum(), internalformat: enum(), pname: enum(), bufSize: integer()
  def getInternalformativ(target, internalformat, pname, bufSize), do: call(5865, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, pname :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getLightfv(light, pname) :: {float(), float(), float(), float()} when light: enum(), pname: enum()
  def getLightfv(light, pname), do: call(5209, <<light :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getLightiv(light, pname) :: {integer(), integer(), integer(), integer()} when light: enum(), pname: enum()
  def getLightiv(light, pname), do: call(5210, <<light :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getMapdv(target, query, v) :: :ok when target: enum(), query: enum(), v: mem()
  def getMapdv(target, query, v) do
    send_bin(v)
    call(5289, <<target :: 32-native-unsigned, query :: 32-native-unsigned>>)
  end

  @spec getMapfv(target, query, v) :: :ok when target: enum(), query: enum(), v: mem()
  def getMapfv(target, query, v) do
    send_bin(v)
    call(5290, <<target :: 32-native-unsigned, query :: 32-native-unsigned>>)
  end

  @spec getMapiv(target, query, v) :: :ok when target: enum(), query: enum(), v: mem()
  def getMapiv(target, query, v) do
    send_bin(v)
    call(5291, <<target :: 32-native-unsigned, query :: 32-native-unsigned>>)
  end

  @spec getMaterialfv(face, pname) :: {float(), float(), float(), float()} when face: enum(), pname: enum()
  def getMaterialfv(face, pname), do: call(5219, <<face :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getMaterialiv(face, pname) :: {integer(), integer(), integer(), integer()} when face: enum(), pname: enum()
  def getMaterialiv(face, pname), do: call(5220, <<face :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getMinmax(target, reset, format, types, values) :: :ok when target: enum(), reset: (0 | 1), format: enum(), types: enum(), values: mem()
  def getMinmax(target, reset, format, types, values) do
    send_bin(values)
    call(5351, <<target :: 32-native-unsigned, reset :: 8-native-unsigned, 0 :: 24, format :: 32-native-unsigned, types :: 32-native-unsigned>>)
  end

  @spec getMinmaxParameterfv(target, pname) :: {float()} when target: enum(), pname: enum()
  def getMinmaxParameterfv(target, pname), do: call(5352, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getMinmaxParameteriv(target, pname) :: {integer()} when target: enum(), pname: enum()
  def getMinmaxParameteriv(target, pname), do: call(5353, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getMultisamplefv(pname, index) :: {float(), float()} when pname: enum(), index: integer()
  def getMultisamplefv(pname, index), do: call(5700, <<pname :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec getNamedStringARB(name, bufSize) :: charlist() when name: charlist(), bufSize: integer()
  def getNamedStringARB(name, bufSize) do
    nameLen = length(name)
    call(5706, <<list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 1, 8), 8)), bufSize :: 32-native-signed>>)
  end

  @spec getNamedStringivARB(name, pname) :: integer() when name: charlist(), pname: enum()
  def getNamedStringivARB(name, pname) do
    nameLen = length(name)
    call(5707, <<list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 1, 8), 8)), pname :: 32-native-unsigned>>)
  end

  @spec getObjectParameterfvARB(obj, pname) :: float() when obj: integer(), pname: enum()
  def getObjectParameterfvARB(obj, pname), do: call(5638, <<obj :: 64-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getObjectParameterivARB(obj, pname) :: integer() when obj: integer(), pname: enum()
  def getObjectParameterivARB(obj, pname), do: call(5639, <<obj :: 64-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getPixelMapfv(map, values) :: :ok when map: enum(), values: mem()
  def getPixelMapfv(map, values) do
    send_bin(values)
    call(5230, <<map :: 32-native-unsigned>>)
  end

  @spec getPixelMapuiv(map, values) :: :ok when map: enum(), values: mem()
  def getPixelMapuiv(map, values) do
    send_bin(values)
    call(5231, <<map :: 32-native-unsigned>>)
  end

  @spec getPixelMapusv(map, values) :: :ok when map: enum(), values: mem()
  def getPixelMapusv(map, values) do
    send_bin(values)
    call(5232, <<map :: 32-native-unsigned>>)
  end

  @spec getPolygonStipple() :: binary()
  def getPolygonStipple(), do: call(5053, <<>>)

  @spec getProgramBinary(program, bufSize) :: {binaryFormat :: enum(), binary :: binary()} when program: integer(), bufSize: integer()
  def getProgramBinary(program, bufSize), do: call(5774, <<program :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getProgramEnvParameterdvARB(target, index) :: {float(), float(), float(), float()} when target: enum(), index: integer()
  def getProgramEnvParameterdvARB(target, index), do: call(5621, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec getProgramEnvParameterfvARB(target, index) :: {float(), float(), float(), float()} when target: enum(), index: integer()
  def getProgramEnvParameterfvARB(target, index), do: call(5622, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec getProgramInfoLog(program, bufSize) :: charlist() when program: integer(), bufSize: integer()
  def getProgramInfoLog(program, bufSize), do: call(5461, <<program :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getProgramLocalParameterdvARB(target, index) :: {float(), float(), float(), float()} when target: enum(), index: integer()
  def getProgramLocalParameterdvARB(target, index), do: call(5623, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec getProgramLocalParameterfvARB(target, index) :: {float(), float(), float(), float()} when target: enum(), index: integer()
  def getProgramLocalParameterfvARB(target, index), do: call(5624, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec getProgramPipelineInfoLog(pipeline, bufSize) :: charlist() when pipeline: integer(), bufSize: integer()
  def getProgramPipelineInfoLog(pipeline, bufSize), do: call(5836, <<pipeline :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getProgramPipelineiv(pipeline, pname) :: integer() when pipeline: integer(), pname: enum()
  def getProgramPipelineiv(pipeline, pname), do: call(5784, <<pipeline :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getProgramStageiv(program, shadertype, pname) :: integer() when program: integer(), shadertype: enum(), pname: enum()
  def getProgramStageiv(program, shadertype, pname), do: call(5755, <<program :: 32-native-unsigned, shadertype :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getProgramStringARB(target, pname, string) :: :ok when target: enum(), pname: enum(), string: mem()
  def getProgramStringARB(target, pname, string) do
    send_bin(string)
    call(5625, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)
  end

  @spec getProgramiv(program, pname) :: integer() when program: integer(), pname: enum()
  def getProgramiv(program, pname), do: call(5460, <<program :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getQueryIndexediv(target, index, pname) :: integer() when target: enum(), index: integer(), pname: enum()
  def getQueryIndexediv(target, index, pname), do: call(5768, <<target :: 32-native-unsigned, index :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getQueryObjecti64v(id, pname) :: integer() when id: integer(), pname: enum()
  def getQueryObjecti64v(id, pname), do: call(5725, <<id :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getQueryObjectiv(id, pname) :: integer() when id: integer(), pname: enum()
  def getQueryObjectiv(id, pname), do: call(5429, <<id :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getQueryObjectui64v(id, pname) :: integer() when id: integer(), pname: enum()
  def getQueryObjectui64v(id, pname), do: call(5726, <<id :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getQueryObjectuiv(id, pname) :: integer() when id: integer(), pname: enum()
  def getQueryObjectuiv(id, pname), do: call(5430, <<id :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getQueryiv(target, pname) :: integer() when target: enum(), pname: enum()
  def getQueryiv(target, pname), do: call(5428, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getRenderbufferParameteriv(target, pname) :: integer() when target: enum(), pname: enum()
  def getRenderbufferParameteriv(target, pname), do: call(5655, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getSamplerParameterIiv(sampler, pname) :: [integer()] when sampler: integer(), pname: enum()
  def getSamplerParameterIiv(sampler, pname), do: call(5721, <<sampler :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getSamplerParameterIuiv(sampler, pname) :: [integer()] when sampler: integer(), pname: enum()
  def getSamplerParameterIuiv(sampler, pname), do: call(5723, <<sampler :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getSamplerParameterfv(sampler, pname) :: [float()] when sampler: integer(), pname: enum()
  def getSamplerParameterfv(sampler, pname), do: call(5722, <<sampler :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getSamplerParameteriv(sampler, pname) :: [integer()] when sampler: integer(), pname: enum()
  def getSamplerParameteriv(sampler, pname), do: call(5720, <<sampler :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getShaderInfoLog(shader, bufSize) :: charlist() when shader: integer(), bufSize: integer()
  def getShaderInfoLog(shader, bufSize), do: call(5463, <<shader :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getShaderPrecisionFormat(shadertype, precisiontype) :: {range :: {integer(), integer()}, precision :: integer()} when shadertype: enum(), precisiontype: enum()
  def getShaderPrecisionFormat(shadertype, precisiontype), do: call(5771, <<shadertype :: 32-native-unsigned, precisiontype :: 32-native-unsigned>>)

  @spec getShaderSource(shader, bufSize) :: charlist() when shader: integer(), bufSize: integer()
  def getShaderSource(shader, bufSize), do: call(5464, <<shader :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getShaderSourceARB(obj, maxLength) :: charlist() when obj: integer(), maxLength: integer()
  def getShaderSourceARB(obj, maxLength), do: call(5646, <<obj :: 64-native-unsigned, maxLength :: 32-native-signed>>)

  @spec getShaderiv(shader, pname) :: integer() when shader: integer(), pname: enum()
  def getShaderiv(shader, pname), do: call(5462, <<shader :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getString(name) :: charlist() when name: enum()
  def getString(name), do: call(5075, <<name :: 32-native-unsigned>>)

  @spec getStringi(name, index) :: charlist() when name: enum(), index: integer()
  def getStringi(name, index), do: call(5577, <<name :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec getSubroutineIndex(program, shadertype, name) :: integer() when program: integer(), shadertype: enum(), name: charlist()
  def getSubroutineIndex(program, shadertype, name) do
    nameLen = length(name)
    call(5750, <<program :: 32-native-unsigned, shadertype :: 32-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 1, 8), 8))>>)
  end

  @spec getSubroutineUniformLocation(program, shadertype, name) :: integer() when program: integer(), shadertype: enum(), name: charlist()
  def getSubroutineUniformLocation(program, shadertype, name) do
    nameLen = length(name)
    call(5749, <<program :: 32-native-unsigned, shadertype :: 32-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 1, 8), 8))>>)
  end

  @spec getSynciv(sync, pname, bufSize) :: [integer()] when sync: integer(), pname: enum(), bufSize: integer()
  def getSynciv(sync, pname, bufSize), do: call(5697, <<sync :: 64-native-unsigned, pname :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getTexEnvfv(target, pname) :: {float(), float(), float(), float()} when target: enum(), pname: enum()
  def getTexEnvfv(target, pname), do: call(5256, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getTexEnviv(target, pname) :: {integer(), integer(), integer(), integer()} when target: enum(), pname: enum()
  def getTexEnviv(target, pname), do: call(5257, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getTexGendv(coord, pname) :: {float(), float(), float(), float()} when coord: enum(), pname: enum()
  def getTexGendv(coord, pname), do: call(5249, <<coord :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getTexGenfv(coord, pname) :: {float(), float(), float(), float()} when coord: enum(), pname: enum()
  def getTexGenfv(coord, pname), do: call(5250, <<coord :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getTexGeniv(coord, pname) :: {integer(), integer(), integer(), integer()} when coord: enum(), pname: enum()
  def getTexGeniv(coord, pname), do: call(5251, <<coord :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getTexImage(target, level, format, type, pixels) :: :ok when target: enum(), level: integer(), format: enum(), type: enum(), pixels: mem()
  def getTexImage(target, level, format, type, pixels) do
    send_bin(pixels)
    call(5270, <<target :: 32-native-unsigned, level :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec getTexLevelParameterfv(target, level, pname) :: {float()} when target: enum(), level: integer(), pname: enum()
  def getTexLevelParameterfv(target, level, pname), do: call(5264, <<target :: 32-native-unsigned, level :: 32-native-signed, pname :: 32-native-unsigned>>)

  @spec getTexLevelParameteriv(target, level, pname) :: {integer()} when target: enum(), level: integer(), pname: enum()
  def getTexLevelParameteriv(target, level, pname), do: call(5265, <<target :: 32-native-unsigned, level :: 32-native-signed, pname :: 32-native-unsigned>>)

  @spec getTexParameterIiv(target, pname) :: {integer(), integer(), integer(), integer()} when target: enum(), pname: enum()
  def getTexParameterIiv(target, pname), do: call(5571, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getTexParameterIuiv(target, pname) :: {integer(), integer(), integer(), integer()} when target: enum(), pname: enum()
  def getTexParameterIuiv(target, pname), do: call(5572, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getTexParameterfv(target, pname) :: {float(), float(), float(), float()} when target: enum(), pname: enum()
  def getTexParameterfv(target, pname), do: call(5262, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getTexParameteriv(target, pname) :: {integer(), integer(), integer(), integer()} when target: enum(), pname: enum()
  def getTexParameteriv(target, pname), do: call(5263, <<target :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getTransformFeedbackVarying(program, index, bufSize) :: {size :: integer(), type :: enum(), name :: charlist()} when program: integer(), index: integer(), bufSize: integer()
  def getTransformFeedbackVarying(program, index, bufSize), do: call(5538, <<program :: 32-native-unsigned, index :: 32-native-unsigned, bufSize :: 32-native-signed>>)

  @spec getUniformBlockIndex(program, uniformBlockName) :: integer() when program: integer(), uniformBlockName: charlist()
  def getUniformBlockIndex(program, uniformBlockName) do
    uniformBlockNameLen = length(uniformBlockName)
    call(5679, <<program :: 32-native-unsigned, list_to_binary([uniformBlockName, 0]) :: binary, 0 :: size(rem(8 - rem(uniformBlockNameLen + 5, 8), 8))>>)
  end

  @spec getUniformIndices(program, uniformNames) :: [integer()] when program: integer(), uniformNames: iolist()
  def getUniformIndices(program, uniformNames) do
    uniformNamesTemp = list_to_binary((for str <- uniformNames do
      [str, 0]
    end))
    uniformNamesLen = length(uniformNames)
    call(5676, <<program :: 32-native-unsigned, uniformNamesLen :: 32-native-unsigned, size(uniformNamesTemp) :: 32-native-unsigned, uniformNamesTemp :: binary, 0 :: size(rem(8 - rem(size(uniformNamesTemp) + 0, 8), 8))>>)
  end

  @spec getUniformLocation(program, name) :: integer() when program: integer(), name: charlist()
  def getUniformLocation(program, name) do
    nameLen = length(name)
    call(5465, <<program :: 32-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 5, 8), 8))>>)
  end

  @spec getUniformLocationARB(programObj, name) :: integer() when programObj: integer(), name: charlist()
  def getUniformLocationARB(programObj, name) do
    nameLen = length(name)
    call(5642, <<programObj :: 64-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 1, 8), 8))>>)
  end

  @spec getUniformSubroutineuiv(shadertype, location) :: {integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer()} when shadertype: enum(), location: integer()
  def getUniformSubroutineuiv(shadertype, location), do: call(5754, <<shadertype :: 32-native-unsigned, location :: 32-native-signed>>)

  @spec getUniformdv(program, location) :: matrix() when program: integer(), location: integer()
  def getUniformdv(program, location), do: call(5748, <<program :: 32-native-unsigned, location :: 32-native-signed>>)

  @spec getUniformfv(program, location) :: matrix() when program: integer(), location: integer()
  def getUniformfv(program, location), do: call(5466, <<program :: 32-native-unsigned, location :: 32-native-signed>>)

  @spec getUniformfvARB(programObj, location) :: matrix() when programObj: integer(), location: integer()
  def getUniformfvARB(programObj, location), do: call(5644, <<programObj :: 64-native-unsigned, location :: 32-native-signed>>)

  @spec getUniformiv(program, location) :: {integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer()} when program: integer(), location: integer()
  def getUniformiv(program, location), do: call(5467, <<program :: 32-native-unsigned, location :: 32-native-signed>>)

  @spec getUniformivARB(programObj, location) :: {integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer()} when programObj: integer(), location: integer()
  def getUniformivARB(programObj, location), do: call(5645, <<programObj :: 64-native-unsigned, location :: 32-native-signed>>)

  @spec getUniformuiv(program, location) :: {integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer(), integer()} when program: integer(), location: integer()
  def getUniformuiv(program, location), do: call(5558, <<program :: 32-native-unsigned, location :: 32-native-signed>>)

  @spec getVertexAttribIiv(index, pname) :: {integer(), integer(), integer(), integer()} when index: integer(), pname: enum()
  def getVertexAttribIiv(index, pname), do: call(5544, <<index :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getVertexAttribIuiv(index, pname) :: {integer(), integer(), integer(), integer()} when index: integer(), pname: enum()
  def getVertexAttribIuiv(index, pname), do: call(5545, <<index :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getVertexAttribLdv(index, pname) :: {float(), float(), float(), float()} when index: integer(), pname: enum()
  def getVertexAttribLdv(index, pname), do: call(5843, <<index :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getVertexAttribdv(index, pname) :: {float(), float(), float(), float()} when index: integer(), pname: enum()
  def getVertexAttribdv(index, pname), do: call(5468, <<index :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getVertexAttribfv(index, pname) :: {float(), float(), float(), float()} when index: integer(), pname: enum()
  def getVertexAttribfv(index, pname), do: call(5469, <<index :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec getVertexAttribiv(index, pname) :: {integer(), integer(), integer(), integer()} when index: integer(), pname: enum()
  def getVertexAttribiv(index, pname), do: call(5470, <<index :: 32-native-unsigned, pname :: 32-native-unsigned>>)

  @spec hint(target, mode) :: :ok when target: enum(), mode: enum()
  def hint(target, mode), do: cast(5078, <<target :: 32-native-unsigned, mode :: 32-native-unsigned>>)

  @spec histogram(target, width, internalformat, sink) :: :ok when target: enum(), width: integer(), internalformat: enum(), sink: (0 | 1)
  def histogram(target, width, internalformat, sink), do: cast(5354, <<target :: 32-native-unsigned, width :: 32-native-signed, internalformat :: 32-native-unsigned, sink :: 8-native-unsigned>>)

  @spec indexMask(mask) :: :ok when mask: integer()
  def indexMask(mask), do: cast(5040, <<mask :: 32-native-unsigned>>)

  @spec indexPointer(type, stride, ptr) :: :ok when type: enum(), stride: integer(), ptr: (offset() | mem())
  def indexPointer(type, stride, ptr) when is_integer(ptr), do: cast(5192, <<type :: 32-native-unsigned, stride :: 32-native-signed, ptr :: 32-native-unsigned>>)

  def indexPointer(type, stride, ptr) do
    send_bin(ptr)
    cast(5193, <<type :: 32-native-unsigned, stride :: 32-native-signed>>)
  end

  @spec indexd(c) :: :ok when c: float()
  def indexd(c), do: cast(5129, <<c :: 64-native-float>>)

  @spec indexdv(c) :: :ok when c: {c :: float()}
  def indexdv({c}), do: indexd(c)

  @spec indexf(c) :: :ok when c: float()
  def indexf(c), do: cast(5130, <<c :: 32-native-float>>)

  @spec indexfv(c) :: :ok when c: {c :: float()}
  def indexfv({c}), do: indexf(c)

  @spec indexi(c) :: :ok when c: integer()
  def indexi(c), do: cast(5131, <<c :: 32-native-signed>>)

  @spec indexiv(c) :: :ok when c: {c :: integer()}
  def indexiv({c}), do: indexi(c)

  @spec indexs(c) :: :ok when c: integer()
  def indexs(c), do: cast(5132, <<c :: 16-native-signed>>)

  @spec indexsv(c) :: :ok when c: {c :: integer()}
  def indexsv({c}), do: indexs(c)

  @spec indexub(c) :: :ok when c: integer()
  def indexub(c), do: cast(5133, <<c :: 8-native-unsigned>>)

  @spec indexubv(c) :: :ok when c: {c :: integer()}
  def indexubv({c}), do: indexub(c)

  @spec initNames() :: :ok
  def initNames(), do: cast(5311, <<>>)

  @spec interleavedArrays(format, stride, pointer) :: :ok when format: enum(), stride: integer(), pointer: (offset() | mem())
  def interleavedArrays(format, stride, pointer) when is_integer(pointer), do: cast(5202, <<format :: 32-native-unsigned, stride :: 32-native-signed, pointer :: 32-native-unsigned>>)

  def interleavedArrays(format, stride, pointer) do
    send_bin(pointer)
    cast(5203, <<format :: 32-native-unsigned, stride :: 32-native-signed>>)
  end

  @spec isBuffer(buffer) :: (0 | 1) when buffer: integer()
  def isBuffer(buffer), do: call(5434, <<buffer :: 32-native-unsigned>>)

  @spec isEnabled(cap) :: (0 | 1) when cap: enum()
  def isEnabled(cap), do: call(5062, <<cap :: 32-native-unsigned>>)

  @spec isEnabledi(target, index) :: (0 | 1) when target: enum(), index: integer()
  def isEnabledi(target, index), do: call(5532, <<target :: 32-native-unsigned, index :: 32-native-unsigned>>)

  @spec isFramebuffer(framebuffer) :: (0 | 1) when framebuffer: integer()
  def isFramebuffer(framebuffer), do: call(5656, <<framebuffer :: 32-native-unsigned>>)

  @spec isList(list) :: (0 | 1) when list: integer()
  def isList(list), do: call(5102, <<list :: 32-native-unsigned>>)

  @spec isNamedStringARB(name) :: (0 | 1) when name: charlist()
  def isNamedStringARB(name) do
    nameLen = length(name)
    call(5705, <<list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 1, 8), 8))>>)
  end

  @spec isProgram(program) :: (0 | 1) when program: integer()
  def isProgram(program), do: call(5471, <<program :: 32-native-unsigned>>)

  @spec isProgramPipeline(pipeline) :: (0 | 1) when pipeline: integer()
  def isProgramPipeline(pipeline), do: call(5783, <<pipeline :: 32-native-unsigned>>)

  @spec isQuery(id) :: (0 | 1) when id: integer()
  def isQuery(id), do: call(5425, <<id :: 32-native-unsigned>>)

  @spec isRenderbuffer(renderbuffer) :: (0 | 1) when renderbuffer: integer()
  def isRenderbuffer(renderbuffer), do: call(5650, <<renderbuffer :: 32-native-unsigned>>)

  @spec isSampler(sampler) :: (0 | 1) when sampler: integer()
  def isSampler(sampler), do: call(5712, <<sampler :: 32-native-unsigned>>)

  @spec isShader(shader) :: (0 | 1) when shader: integer()
  def isShader(shader), do: call(5472, <<shader :: 32-native-unsigned>>)

  @spec isSync(sync) :: (0 | 1) when sync: integer()
  def isSync(sync), do: call(5692, <<sync :: 64-native-unsigned>>)

  @spec isTexture(texture) :: (0 | 1) when texture: integer()
  def isTexture(texture), do: call(5276, <<texture :: 32-native-unsigned>>)

  @spec isTransformFeedback(id) :: (0 | 1) when id: integer()
  def isTransformFeedback(id), do: call(5761, <<id :: 32-native-unsigned>>)

  @spec isVertexArray(array) :: (0 | 1) when array: integer()
  def isVertexArray(array), do: call(5675, <<array :: 32-native-unsigned>>)

  @spec lightModelf(pname, param) :: :ok when pname: enum(), param: float()
  def lightModelf(pname, param), do: cast(5211, <<pname :: 32-native-unsigned, param :: 32-native-float>>)

  @spec lightModelfv(pname, params) :: :ok when pname: enum(), params: tuple()
  def lightModelfv(pname, params) do
    cast(5213, <<pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(0 + size(params), 2) * 32)>>)
  end

  @spec lightModeli(pname, param) :: :ok when pname: enum(), param: integer()
  def lightModeli(pname, param), do: cast(5212, <<pname :: 32-native-unsigned, param :: 32-native-signed>>)

  @spec lightModeliv(pname, params) :: :ok when pname: enum(), params: tuple()
  def lightModeliv(pname, params) do
    cast(5214, <<pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(0 + size(params), 2) * 32)>>)
  end

  @spec lightf(light, pname, param) :: :ok when light: enum(), pname: enum(), param: float()
  def lightf(light, pname, param), do: cast(5205, <<light :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-float>>)

  @spec lightfv(light, pname, params) :: :ok when light: enum(), pname: enum(), params: tuple()
  def lightfv(light, pname, params) do
    cast(5207, <<light :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec lighti(light, pname, param) :: :ok when light: enum(), pname: enum(), param: integer()
  def lighti(light, pname, param), do: cast(5206, <<light :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-signed>>)

  @spec lightiv(light, pname, params) :: :ok when light: enum(), pname: enum(), params: tuple()
  def lightiv(light, pname, params) do
    cast(5208, <<light :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec lineStipple(factor, pattern) :: :ok when factor: integer(), pattern: integer()
  def lineStipple(factor, pattern), do: cast(5049, <<factor :: 32-native-signed, pattern :: 16-native-unsigned>>)

  @spec lineWidth(width) :: :ok when width: float()
  def lineWidth(width), do: cast(5048, <<width :: 32-native-float>>)

  @spec linkProgram(program) :: :ok when program: integer()
  def linkProgram(program), do: cast(5473, <<program :: 32-native-unsigned>>)

  @spec linkProgramARB(programObj) :: :ok when programObj: integer()
  def linkProgramARB(programObj), do: cast(5635, <<programObj :: 64-native-unsigned>>)

  @spec listBase(base) :: :ok when base: integer()
  def listBase(base), do: cast(5109, <<base :: 32-native-unsigned>>)

  @spec loadIdentity() :: :ok
  def loadIdentity(), do: cast(5091, <<>>)

  @spec loadMatrixd(m) :: :ok when m: matrix()
  def loadMatrixd({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5092, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, m13 :: 64-native-float, m14 :: 64-native-float, m15 :: 64-native-float, m16 :: 64-native-float>>)

  def loadMatrixd({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5092, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, 0 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, 0 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, 0 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, 1 :: 64-native-float>>)

  @spec loadMatrixf(m) :: :ok when m: matrix()
  def loadMatrixf({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5093, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, m13 :: 32-native-float, m14 :: 32-native-float, m15 :: 32-native-float, m16 :: 32-native-float>>)

  def loadMatrixf({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5093, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, 0 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, 0 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, 0 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, 1 :: 32-native-float>>)

  @spec loadName(name) :: :ok when name: integer()
  def loadName(name), do: cast(5312, <<name :: 32-native-unsigned>>)

  @spec loadTransposeMatrixd(m) :: :ok when m: matrix()
  def loadTransposeMatrixd({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5391, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, m13 :: 64-native-float, m14 :: 64-native-float, m15 :: 64-native-float, m16 :: 64-native-float>>)

  def loadTransposeMatrixd({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5391, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, 0 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, 0 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, 0 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, 1 :: 64-native-float>>)

  @spec loadTransposeMatrixdARB(m) :: :ok when m: matrix()
  def loadTransposeMatrixdARB({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5593, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, m13 :: 64-native-float, m14 :: 64-native-float, m15 :: 64-native-float, m16 :: 64-native-float>>)

  def loadTransposeMatrixdARB({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5593, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, 0 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, 0 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, 0 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, 1 :: 64-native-float>>)

  @spec loadTransposeMatrixf(m) :: :ok when m: matrix()
  def loadTransposeMatrixf({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5390, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, m13 :: 32-native-float, m14 :: 32-native-float, m15 :: 32-native-float, m16 :: 32-native-float>>)

  def loadTransposeMatrixf({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5390, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, 0 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, 0 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, 0 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, 1 :: 32-native-float>>)

  @spec loadTransposeMatrixfARB(m) :: :ok when m: matrix()
  def loadTransposeMatrixfARB({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5592, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, m13 :: 32-native-float, m14 :: 32-native-float, m15 :: 32-native-float, m16 :: 32-native-float>>)

  def loadTransposeMatrixfARB({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5592, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, 0 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, 0 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, 0 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, 1 :: 32-native-float>>)

  @spec logicOp(opcode) :: :ok when opcode: enum()
  def logicOp(opcode), do: cast(5044, <<opcode :: 32-native-unsigned>>)

  @spec map1d(target, u1, u2, stride, order, points) :: :ok when target: enum(), u1: float(), u2: float(), stride: integer(), order: integer(), points: binary()
  def map1d(target, u1, u2, stride, order, points) do
    send_bin(points)
    cast(5285, <<target :: 32-native-unsigned, 0 :: 32, u1 :: 64-native-float, u2 :: 64-native-float, stride :: 32-native-signed, order :: 32-native-signed>>)
  end

  @spec map1f(target, u1, u2, stride, order, points) :: :ok when target: enum(), u1: float(), u2: float(), stride: integer(), order: integer(), points: binary()
  def map1f(target, u1, u2, stride, order, points) do
    send_bin(points)
    cast(5286, <<target :: 32-native-unsigned, u1 :: 32-native-float, u2 :: 32-native-float, stride :: 32-native-signed, order :: 32-native-signed>>)
  end

  @spec map2d(target, u1, u2, ustride, uorder, v1, v2, vstride, vorder, points) :: :ok when target: enum(), u1: float(), u2: float(), ustride: integer(), uorder: integer(), v1: float(), v2: float(), vstride: integer(), vorder: integer(), points: binary()
  def map2d(target, u1, u2, ustride, uorder, v1, v2, vstride, vorder, points) do
    send_bin(points)
    cast(5287, <<target :: 32-native-unsigned, 0 :: 32, u1 :: 64-native-float, u2 :: 64-native-float, ustride :: 32-native-signed, uorder :: 32-native-signed, v1 :: 64-native-float, v2 :: 64-native-float, vstride :: 32-native-signed, vorder :: 32-native-signed>>)
  end

  @spec map2f(target, u1, u2, ustride, uorder, v1, v2, vstride, vorder, points) :: :ok when target: enum(), u1: float(), u2: float(), ustride: integer(), uorder: integer(), v1: float(), v2: float(), vstride: integer(), vorder: integer(), points: binary()
  def map2f(target, u1, u2, ustride, uorder, v1, v2, vstride, vorder, points) do
    send_bin(points)
    cast(5288, <<target :: 32-native-unsigned, u1 :: 32-native-float, u2 :: 32-native-float, ustride :: 32-native-signed, uorder :: 32-native-signed, v1 :: 32-native-float, v2 :: 32-native-float, vstride :: 32-native-signed, vorder :: 32-native-signed>>)
  end

  @spec mapGrid1d(un, u1, u2) :: :ok when un: integer(), u1: float(), u2: float()
  def mapGrid1d(un, u1, u2), do: cast(5296, <<un :: 32-native-signed, 0 :: 32, u1 :: 64-native-float, u2 :: 64-native-float>>)

  @spec mapGrid1f(un, u1, u2) :: :ok when un: integer(), u1: float(), u2: float()
  def mapGrid1f(un, u1, u2), do: cast(5297, <<un :: 32-native-signed, u1 :: 32-native-float, u2 :: 32-native-float>>)

  @spec mapGrid2d(un, u1, u2, vn, v1, v2) :: :ok when un: integer(), u1: float(), u2: float(), vn: integer(), v1: float(), v2: float()
  def mapGrid2d(un, u1, u2, vn, v1, v2), do: cast(5298, <<un :: 32-native-signed, 0 :: 32, u1 :: 64-native-float, u2 :: 64-native-float, vn :: 32-native-signed, 0 :: 32, v1 :: 64-native-float, v2 :: 64-native-float>>)

  @spec mapGrid2f(un, u1, u2, vn, v1, v2) :: :ok when un: integer(), u1: float(), u2: float(), vn: integer(), v1: float(), v2: float()
  def mapGrid2f(un, u1, u2, vn, v1, v2), do: cast(5299, <<un :: 32-native-signed, u1 :: 32-native-float, u2 :: 32-native-float, vn :: 32-native-signed, v1 :: 32-native-float, v2 :: 32-native-float>>)

  @spec materialf(face, pname, param) :: :ok when face: enum(), pname: enum(), param: float()
  def materialf(face, pname, param), do: cast(5215, <<face :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-float>>)

  @spec materialfv(face, pname, params) :: :ok when face: enum(), pname: enum(), params: tuple()
  def materialfv(face, pname, params) do
    cast(5217, <<face :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec materiali(face, pname, param) :: :ok when face: enum(), pname: enum(), param: integer()
  def materiali(face, pname, param), do: cast(5216, <<face :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-signed>>)

  @spec materialiv(face, pname, params) :: :ok when face: enum(), pname: enum(), params: tuple()
  def materialiv(face, pname, params) do
    cast(5218, <<face :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec matrixIndexubvARB(indices) :: :ok when indices: [integer()]
  def matrixIndexubvARB(indices) do
    indicesLen = length(indices)
    cast(5606, <<indicesLen :: 32-native-unsigned, (for c <- indices do
      <<c :: 8-native-unsigned>>
    end) :: binary, 0 :: size(rem(8 - rem(indicesLen + 4, 8), 8))>>)
  end

  @spec matrixIndexuivARB(indices) :: :ok when indices: [integer()]
  def matrixIndexuivARB(indices) do
    indicesLen = length(indices)
    cast(5608, <<indicesLen :: 32-native-unsigned, (for c <- indices do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + indicesLen, 2) * 32)>>)
  end

  @spec matrixIndexusvARB(indices) :: :ok when indices: [integer()]
  def matrixIndexusvARB(indices) do
    indicesLen = length(indices)
    cast(5607, <<indicesLen :: 32-native-unsigned, (for c <- indices do
      <<c :: 16-native-unsigned>>
    end) :: binary, 0 :: size(rem(8 - rem(indicesLen * 2 + 4, 8), 8))>>)
  end

  @spec matrixMode(mode) :: :ok when mode: enum()
  def matrixMode(mode), do: cast(5085, <<mode :: 32-native-unsigned>>)

  @spec memoryBarrier(barriers) :: :ok when barriers: integer()
  def memoryBarrier(barriers), do: cast(5867, <<barriers :: 32-native-unsigned>>)

  @spec minSampleShading(value) :: :ok when value: clamp()
  def minSampleShading(value), do: cast(5587, <<value :: 32-native-float>>)

  @spec minmax(target, internalformat, sink) :: :ok when target: enum(), internalformat: enum(), sink: (0 | 1)
  def minmax(target, internalformat, sink), do: cast(5355, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, sink :: 8-native-unsigned>>)

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @spec multMatrixd(m) :: :ok when m: matrix()
  def multMatrixd({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5094, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, m13 :: 64-native-float, m14 :: 64-native-float, m15 :: 64-native-float, m16 :: 64-native-float>>)

  def multMatrixd({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5094, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, 0 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, 0 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, 0 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, 1 :: 64-native-float>>)

  @spec multMatrixf(m) :: :ok when m: matrix()
  def multMatrixf({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5095, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, m13 :: 32-native-float, m14 :: 32-native-float, m15 :: 32-native-float, m16 :: 32-native-float>>)

  def multMatrixf({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5095, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, 0 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, 0 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, 0 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, 1 :: 32-native-float>>)

  @spec multTransposeMatrixd(m) :: :ok when m: matrix()
  def multTransposeMatrixd({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5393, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, m13 :: 64-native-float, m14 :: 64-native-float, m15 :: 64-native-float, m16 :: 64-native-float>>)

  def multTransposeMatrixd({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5393, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, 0 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, 0 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, 0 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, 1 :: 64-native-float>>)

  @spec multTransposeMatrixdARB(m) :: :ok when m: matrix()
  def multTransposeMatrixdARB({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5595, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, m13 :: 64-native-float, m14 :: 64-native-float, m15 :: 64-native-float, m16 :: 64-native-float>>)

  def multTransposeMatrixdARB({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5595, <<m1 :: 64-native-float, m2 :: 64-native-float, m3 :: 64-native-float, 0 :: 64-native-float, m4 :: 64-native-float, m5 :: 64-native-float, m6 :: 64-native-float, 0 :: 64-native-float, m7 :: 64-native-float, m8 :: 64-native-float, m9 :: 64-native-float, 0 :: 64-native-float, m10 :: 64-native-float, m11 :: 64-native-float, m12 :: 64-native-float, 1 :: 64-native-float>>)

  @spec multTransposeMatrixf(m) :: :ok when m: matrix()
  def multTransposeMatrixf({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5392, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, m13 :: 32-native-float, m14 :: 32-native-float, m15 :: 32-native-float, m16 :: 32-native-float>>)

  def multTransposeMatrixf({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5392, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, 0 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, 0 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, 0 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, 1 :: 32-native-float>>)

  @spec multTransposeMatrixfARB(m) :: :ok when m: matrix()
  def multTransposeMatrixfARB({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16}), do: cast(5594, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, m13 :: 32-native-float, m14 :: 32-native-float, m15 :: 32-native-float, m16 :: 32-native-float>>)

  def multTransposeMatrixfARB({m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12}), do: cast(5594, <<m1 :: 32-native-float, m2 :: 32-native-float, m3 :: 32-native-float, 0 :: 32-native-float, m4 :: 32-native-float, m5 :: 32-native-float, m6 :: 32-native-float, 0 :: 32-native-float, m7 :: 32-native-float, m8 :: 32-native-float, m9 :: 32-native-float, 0 :: 32-native-float, m10 :: 32-native-float, m11 :: 32-native-float, m12 :: 32-native-float, 1 :: 32-native-float>>)

  @spec multiDrawArrays(mode, first, count) :: :ok when mode: enum(), first: ([integer()] | mem()), count: ([integer()] | mem())
  def multiDrawArrays(mode, first, count) when is_list(first) and is_list(count) do
    firstLen = length(first)
    countLen = length(count)
    cast(5395, <<mode :: 32-native-unsigned, firstLen :: 32-native-unsigned, (for c <- first do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(firstLen, 2) * 32), countLen :: 32-native-unsigned, (for c <- count do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + countLen, 2) * 32)>>)
  end

  def multiDrawArrays(mode, first, count) do
    send_bin(first)
    firstLen = div(byte_size((cond do
      is_binary(first) ->
        first
      is_tuple(first) ->
        element(2, first)
    end)), 4)
    send_bin(count)
    countLen = div(byte_size((cond do
      is_binary(count) ->
        count
      is_tuple(count) ->
        element(2, count)
    end)), 4)
    cast(5396, <<mode :: 32-native-unsigned, firstLen :: 32-native-signed, countLen :: 32-native-signed>>)
  end

  @spec multiTexCoord1d(target, s) :: :ok when target: enum(), s: float()
  def multiTexCoord1d(target, s), do: cast(5374, <<target :: 32-native-unsigned, 0 :: 32, s :: 64-native-float>>)

  @spec multiTexCoord1dv(target :: enum(), v) :: :ok when v: {s :: float()}
  def multiTexCoord1dv(target, {s}), do: multiTexCoord1d(target, s)

  @spec multiTexCoord1f(target, s) :: :ok when target: enum(), s: float()
  def multiTexCoord1f(target, s), do: cast(5375, <<target :: 32-native-unsigned, s :: 32-native-float>>)

  @spec multiTexCoord1fv(target :: enum(), v) :: :ok when v: {s :: float()}
  def multiTexCoord1fv(target, {s}), do: multiTexCoord1f(target, s)

  @spec multiTexCoord1i(target, s) :: :ok when target: enum(), s: integer()
  def multiTexCoord1i(target, s), do: cast(5376, <<target :: 32-native-unsigned, s :: 32-native-signed>>)

  @spec multiTexCoord1iv(target :: enum(), v) :: :ok when v: {s :: integer()}
  def multiTexCoord1iv(target, {s}), do: multiTexCoord1i(target, s)

  @spec multiTexCoord1s(target, s) :: :ok when target: enum(), s: integer()
  def multiTexCoord1s(target, s), do: cast(5377, <<target :: 32-native-unsigned, s :: 16-native-signed>>)

  @spec multiTexCoord1sv(target :: enum(), v) :: :ok when v: {s :: integer()}
  def multiTexCoord1sv(target, {s}), do: multiTexCoord1s(target, s)

  @spec multiTexCoord2d(target, s, t) :: :ok when target: enum(), s: float(), t: float()
  def multiTexCoord2d(target, s, t), do: cast(5378, <<target :: 32-native-unsigned, 0 :: 32, s :: 64-native-float, t :: 64-native-float>>)

  @spec multiTexCoord2dv(target :: enum(), v) :: :ok when v: {s :: float(), t :: float()}
  def multiTexCoord2dv(target, {s, t}), do: multiTexCoord2d(target, s, t)

  @spec multiTexCoord2f(target, s, t) :: :ok when target: enum(), s: float(), t: float()
  def multiTexCoord2f(target, s, t), do: cast(5379, <<target :: 32-native-unsigned, s :: 32-native-float, t :: 32-native-float>>)

  @spec multiTexCoord2fv(target :: enum(), v) :: :ok when v: {s :: float(), t :: float()}
  def multiTexCoord2fv(target, {s, t}), do: multiTexCoord2f(target, s, t)

  @spec multiTexCoord2i(target, s, t) :: :ok when target: enum(), s: integer(), t: integer()
  def multiTexCoord2i(target, s, t), do: cast(5380, <<target :: 32-native-unsigned, s :: 32-native-signed, t :: 32-native-signed>>)

  @spec multiTexCoord2iv(target :: enum(), v) :: :ok when v: {s :: integer(), t :: integer()}
  def multiTexCoord2iv(target, {s, t}), do: multiTexCoord2i(target, s, t)

  @spec multiTexCoord2s(target, s, t) :: :ok when target: enum(), s: integer(), t: integer()
  def multiTexCoord2s(target, s, t), do: cast(5381, <<target :: 32-native-unsigned, s :: 16-native-signed, t :: 16-native-signed>>)

  @spec multiTexCoord2sv(target :: enum(), v) :: :ok when v: {s :: integer(), t :: integer()}
  def multiTexCoord2sv(target, {s, t}), do: multiTexCoord2s(target, s, t)

  @spec multiTexCoord3d(target, s, t, r) :: :ok when target: enum(), s: float(), t: float(), r: float()
  def multiTexCoord3d(target, s, t, r), do: cast(5382, <<target :: 32-native-unsigned, 0 :: 32, s :: 64-native-float, t :: 64-native-float, r :: 64-native-float>>)

  @spec multiTexCoord3dv(target :: enum(), v) :: :ok when v: {s :: float(), t :: float(), r :: float()}
  def multiTexCoord3dv(target, {s, t, r}), do: multiTexCoord3d(target, s, t, r)

  @spec multiTexCoord3f(target, s, t, r) :: :ok when target: enum(), s: float(), t: float(), r: float()
  def multiTexCoord3f(target, s, t, r), do: cast(5383, <<target :: 32-native-unsigned, s :: 32-native-float, t :: 32-native-float, r :: 32-native-float>>)

  @spec multiTexCoord3fv(target :: enum(), v) :: :ok when v: {s :: float(), t :: float(), r :: float()}
  def multiTexCoord3fv(target, {s, t, r}), do: multiTexCoord3f(target, s, t, r)

  @spec multiTexCoord3i(target, s, t, r) :: :ok when target: enum(), s: integer(), t: integer(), r: integer()
  def multiTexCoord3i(target, s, t, r), do: cast(5384, <<target :: 32-native-unsigned, s :: 32-native-signed, t :: 32-native-signed, r :: 32-native-signed>>)

  @spec multiTexCoord3iv(target :: enum(), v) :: :ok when v: {s :: integer(), t :: integer(), r :: integer()}
  def multiTexCoord3iv(target, {s, t, r}), do: multiTexCoord3i(target, s, t, r)

  @spec multiTexCoord3s(target, s, t, r) :: :ok when target: enum(), s: integer(), t: integer(), r: integer()
  def multiTexCoord3s(target, s, t, r), do: cast(5385, <<target :: 32-native-unsigned, s :: 16-native-signed, t :: 16-native-signed, r :: 16-native-signed>>)

  @spec multiTexCoord3sv(target :: enum(), v) :: :ok when v: {s :: integer(), t :: integer(), r :: integer()}
  def multiTexCoord3sv(target, {s, t, r}), do: multiTexCoord3s(target, s, t, r)

  @spec multiTexCoord4d(target, s, t, r, q) :: :ok when target: enum(), s: float(), t: float(), r: float(), q: float()
  def multiTexCoord4d(target, s, t, r, q), do: cast(5386, <<target :: 32-native-unsigned, 0 :: 32, s :: 64-native-float, t :: 64-native-float, r :: 64-native-float, q :: 64-native-float>>)

  @spec multiTexCoord4dv(target :: enum(), v) :: :ok when v: {s :: float(), t :: float(), r :: float(), q :: float()}
  def multiTexCoord4dv(target, {s, t, r, q}), do: multiTexCoord4d(target, s, t, r, q)

  @spec multiTexCoord4f(target, s, t, r, q) :: :ok when target: enum(), s: float(), t: float(), r: float(), q: float()
  def multiTexCoord4f(target, s, t, r, q), do: cast(5387, <<target :: 32-native-unsigned, s :: 32-native-float, t :: 32-native-float, r :: 32-native-float, q :: 32-native-float>>)

  @spec multiTexCoord4fv(target :: enum(), v) :: :ok when v: {s :: float(), t :: float(), r :: float(), q :: float()}
  def multiTexCoord4fv(target, {s, t, r, q}), do: multiTexCoord4f(target, s, t, r, q)

  @spec multiTexCoord4i(target, s, t, r, q) :: :ok when target: enum(), s: integer(), t: integer(), r: integer(), q: integer()
  def multiTexCoord4i(target, s, t, r, q), do: cast(5388, <<target :: 32-native-unsigned, s :: 32-native-signed, t :: 32-native-signed, r :: 32-native-signed, q :: 32-native-signed>>)

  @spec multiTexCoord4iv(target :: enum(), v) :: :ok when v: {s :: integer(), t :: integer(), r :: integer(), q :: integer()}
  def multiTexCoord4iv(target, {s, t, r, q}), do: multiTexCoord4i(target, s, t, r, q)

  @spec multiTexCoord4s(target, s, t, r, q) :: :ok when target: enum(), s: integer(), t: integer(), r: integer(), q: integer()
  def multiTexCoord4s(target, s, t, r, q), do: cast(5389, <<target :: 32-native-unsigned, s :: 16-native-signed, t :: 16-native-signed, r :: 16-native-signed, q :: 16-native-signed>>)

  @spec multiTexCoord4sv(target :: enum(), v) :: :ok when v: {s :: integer(), t :: integer(), r :: integer(), q :: integer()}
  def multiTexCoord4sv(target, {s, t, r, q}), do: multiTexCoord4s(target, s, t, r, q)

  @spec namedStringARB(type, name, string) :: :ok when type: enum(), name: charlist(), string: charlist()
  def namedStringARB(type, name, string) do
    nameLen = length(name)
    stringLen = length(string)
    cast(5702, <<type :: 32-native-unsigned, list_to_binary([name, 0]) :: binary, 0 :: size(rem(8 - rem(nameLen + 5, 8), 8)), list_to_binary([string, 0]) :: binary, 0 :: size(rem(8 - rem(stringLen + 1, 8), 8))>>)
  end

  @spec newList(list, mode) :: :ok when list: integer(), mode: enum()
  def newList(list, mode), do: cast(5105, <<list :: 32-native-unsigned, mode :: 32-native-unsigned>>)

  @spec normal3b(nx, ny, nz) :: :ok when nx: integer(), ny: integer(), nz: integer()
  def normal3b(nx, ny, nz), do: cast(5124, <<nx :: 8-native-signed, ny :: 8-native-signed, nz :: 8-native-signed>>)

  @spec normal3bv(v) :: :ok when v: {nx :: integer(), ny :: integer(), nz :: integer()}
  def normal3bv({nx, ny, nz}), do: normal3b(nx, ny, nz)

  @spec normal3d(nx, ny, nz) :: :ok when nx: float(), ny: float(), nz: float()
  def normal3d(nx, ny, nz), do: cast(5125, <<nx :: 64-native-float, ny :: 64-native-float, nz :: 64-native-float>>)

  @spec normal3dv(v) :: :ok when v: {nx :: float(), ny :: float(), nz :: float()}
  def normal3dv({nx, ny, nz}), do: normal3d(nx, ny, nz)

  @spec normal3f(nx, ny, nz) :: :ok when nx: float(), ny: float(), nz: float()
  def normal3f(nx, ny, nz), do: cast(5126, <<nx :: 32-native-float, ny :: 32-native-float, nz :: 32-native-float>>)

  @spec normal3fv(v) :: :ok when v: {nx :: float(), ny :: float(), nz :: float()}
  def normal3fv({nx, ny, nz}), do: normal3f(nx, ny, nz)

  @spec normal3i(nx, ny, nz) :: :ok when nx: integer(), ny: integer(), nz: integer()
  def normal3i(nx, ny, nz), do: cast(5127, <<nx :: 32-native-signed, ny :: 32-native-signed, nz :: 32-native-signed>>)

  @spec normal3iv(v) :: :ok when v: {nx :: integer(), ny :: integer(), nz :: integer()}
  def normal3iv({nx, ny, nz}), do: normal3i(nx, ny, nz)

  @spec normal3s(nx, ny, nz) :: :ok when nx: integer(), ny: integer(), nz: integer()
  def normal3s(nx, ny, nz), do: cast(5128, <<nx :: 16-native-signed, ny :: 16-native-signed, nz :: 16-native-signed>>)

  @spec normal3sv(v) :: :ok when v: {nx :: integer(), ny :: integer(), nz :: integer()}
  def normal3sv({nx, ny, nz}), do: normal3s(nx, ny, nz)

  @spec normalPointer(type, stride, ptr) :: :ok when type: enum(), stride: integer(), ptr: (offset() | mem())
  def normalPointer(type, stride, ptr) when is_integer(ptr), do: cast(5188, <<type :: 32-native-unsigned, stride :: 32-native-signed, ptr :: 32-native-unsigned>>)

  def normalPointer(type, stride, ptr) do
    send_bin(ptr)
    cast(5189, <<type :: 32-native-unsigned, stride :: 32-native-signed>>)
  end

  @spec ortho(left, right, bottom, top, near_val, far_val) :: :ok when left: float(), right: float(), bottom: float(), top: float(), near_val: float(), far_val: float()
  def ortho(left, right, bottom, top, near_val, far_val), do: cast(5086, <<left :: 64-native-float, right :: 64-native-float, bottom :: 64-native-float, top :: 64-native-float, near_val :: 64-native-float, far_val :: 64-native-float>>)

  @spec passThrough(token) :: :ok when token: float()
  def passThrough(token), do: cast(5309, <<token :: 32-native-float>>)

  @spec patchParameterfv(pname, values) :: :ok when pname: enum(), values: [float()]
  def patchParameterfv(pname, values) do
    valuesLen = length(values)
    cast(5757, <<pname :: 32-native-unsigned, valuesLen :: 32-native-unsigned, (for c <- values do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(valuesLen, 2) * 32)>>)
  end

  @spec patchParameteri(pname, value) :: :ok when pname: enum(), value: integer()
  def patchParameteri(pname, value), do: cast(5756, <<pname :: 32-native-unsigned, value :: 32-native-signed>>)

  @spec pauseTransformFeedback() :: :ok
  def pauseTransformFeedback(), do: cast(5762, <<>>)

  @spec pixelMapfv(map, mapsize, values) :: :ok when map: enum(), mapsize: integer(), values: binary()
  def pixelMapfv(map, mapsize, values) do
    send_bin(values)
    cast(5227, <<map :: 32-native-unsigned, mapsize :: 32-native-signed>>)
  end

  @spec pixelMapuiv(map, mapsize, values) :: :ok when map: enum(), mapsize: integer(), values: binary()
  def pixelMapuiv(map, mapsize, values) do
    send_bin(values)
    cast(5228, <<map :: 32-native-unsigned, mapsize :: 32-native-signed>>)
  end

  @spec pixelMapusv(map, mapsize, values) :: :ok when map: enum(), mapsize: integer(), values: binary()
  def pixelMapusv(map, mapsize, values) do
    send_bin(values)
    cast(5229, <<map :: 32-native-unsigned, mapsize :: 32-native-signed>>)
  end

  @spec pixelStoref(pname, param) :: :ok when pname: enum(), param: float()
  def pixelStoref(pname, param), do: cast(5223, <<pname :: 32-native-unsigned, param :: 32-native-float>>)

  @spec pixelStorei(pname, param) :: :ok when pname: enum(), param: integer()
  def pixelStorei(pname, param), do: cast(5224, <<pname :: 32-native-unsigned, param :: 32-native-signed>>)

  @spec pixelTransferf(pname, param) :: :ok when pname: enum(), param: float()
  def pixelTransferf(pname, param), do: cast(5225, <<pname :: 32-native-unsigned, param :: 32-native-float>>)

  @spec pixelTransferi(pname, param) :: :ok when pname: enum(), param: integer()
  def pixelTransferi(pname, param), do: cast(5226, <<pname :: 32-native-unsigned, param :: 32-native-signed>>)

  @spec pixelZoom(xfactor, yfactor) :: :ok when xfactor: float(), yfactor: float()
  def pixelZoom(xfactor, yfactor), do: cast(5222, <<xfactor :: 32-native-float, yfactor :: 32-native-float>>)

  @spec pointParameterf(pname, param) :: :ok when pname: enum(), param: float()
  def pointParameterf(pname, param), do: cast(5397, <<pname :: 32-native-unsigned, param :: 32-native-float>>)

  @spec pointParameterfv(pname, params) :: :ok when pname: enum(), params: tuple()
  def pointParameterfv(pname, params) do
    cast(5398, <<pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(0 + size(params), 2) * 32)>>)
  end

  @spec pointParameteri(pname, param) :: :ok when pname: enum(), param: integer()
  def pointParameteri(pname, param), do: cast(5399, <<pname :: 32-native-unsigned, param :: 32-native-signed>>)

  @spec pointParameteriv(pname, params) :: :ok when pname: enum(), params: tuple()
  def pointParameteriv(pname, params) do
    cast(5400, <<pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(0 + size(params), 2) * 32)>>)
  end

  @spec pointSize(size) :: :ok when size: float()
  def pointSize(size), do: cast(5047, <<size :: 32-native-float>>)

  @spec polygonMode(face, mode) :: :ok when face: enum(), mode: enum()
  def polygonMode(face, mode), do: cast(5050, <<face :: 32-native-unsigned, mode :: 32-native-unsigned>>)

  @spec polygonOffset(factor, units) :: :ok when factor: float(), units: float()
  def polygonOffset(factor, units), do: cast(5051, <<factor :: 32-native-float, units :: 32-native-float>>)

  @spec polygonStipple(mask) :: :ok when mask: binary()
  def polygonStipple(mask) do
    send_bin(mask)
    cast(5052, <<>>)
  end

  @spec popAttrib() :: :ok
  def popAttrib(), do: cast(5070, <<>>)

  @spec popClientAttrib() :: :ok
  def popClientAttrib(), do: cast(5072, <<>>)

  @spec popMatrix() :: :ok
  def popMatrix(), do: cast(5090, <<>>)

  @spec popName() :: :ok
  def popName(), do: cast(5314, <<>>)

  @spec primitiveRestartIndex(index) :: :ok when index: integer()
  def primitiveRestartIndex(index), do: cast(5582, <<index :: 32-native-unsigned>>)

  @spec prioritizeTextures(textures, priorities) :: :ok when textures: [integer()], priorities: [clamp()]
  def prioritizeTextures(textures, priorities) do
    texturesLen = length(textures)
    prioritiesLen = length(priorities)
    cast(5274, <<texturesLen :: 32-native-unsigned, (for c <- textures do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + texturesLen, 2) * 32), prioritiesLen :: 32-native-unsigned, (for c <- priorities do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(1 + prioritiesLen, 2) * 32)>>)
  end

  @spec programBinary(program, binaryFormat, binary) :: :ok when program: integer(), binaryFormat: enum(), binary: binary()
  def programBinary(program, binaryFormat, binary) do
    send_bin(binary)
    cast(5775, <<program :: 32-native-unsigned, binaryFormat :: 32-native-unsigned>>)
  end

  @spec programEnvParameter4dARB(target, index, x, y, z, w) :: :ok when target: enum(), index: integer(), x: float(), y: float(), z: float(), w: float()
  def programEnvParameter4dARB(target, index, x, y, z, w), do: cast(5613, <<target :: 32-native-unsigned, index :: 32-native-unsigned, x :: 64-native-float, y :: 64-native-float, z :: 64-native-float, w :: 64-native-float>>)

  @spec programEnvParameter4dvARB(target, index, params) :: :ok when target: enum(), index: integer(), params: {float(), float(), float(), float()}
  def programEnvParameter4dvARB(target, index, {p1, p2, p3, p4}), do: cast(5614, <<target :: 32-native-unsigned, index :: 32-native-unsigned, p1 :: 64-native-float, p2 :: 64-native-float, p3 :: 64-native-float, p4 :: 64-native-float>>)

  @spec programEnvParameter4fARB(target, index, x, y, z, w) :: :ok when target: enum(), index: integer(), x: float(), y: float(), z: float(), w: float()
  def programEnvParameter4fARB(target, index, x, y, z, w), do: cast(5615, <<target :: 32-native-unsigned, index :: 32-native-unsigned, x :: 32-native-float, y :: 32-native-float, z :: 32-native-float, w :: 32-native-float>>)

  @spec programEnvParameter4fvARB(target, index, params) :: :ok when target: enum(), index: integer(), params: {float(), float(), float(), float()}
  def programEnvParameter4fvARB(target, index, {p1, p2, p3, p4}), do: cast(5616, <<target :: 32-native-unsigned, index :: 32-native-unsigned, p1 :: 32-native-float, p2 :: 32-native-float, p3 :: 32-native-float, p4 :: 32-native-float>>)

  @spec programLocalParameter4dARB(target, index, x, y, z, w) :: :ok when target: enum(), index: integer(), x: float(), y: float(), z: float(), w: float()
  def programLocalParameter4dARB(target, index, x, y, z, w), do: cast(5617, <<target :: 32-native-unsigned, index :: 32-native-unsigned, x :: 64-native-float, y :: 64-native-float, z :: 64-native-float, w :: 64-native-float>>)

  @spec programLocalParameter4dvARB(target, index, params) :: :ok when target: enum(), index: integer(), params: {float(), float(), float(), float()}
  def programLocalParameter4dvARB(target, index, {p1, p2, p3, p4}), do: cast(5618, <<target :: 32-native-unsigned, index :: 32-native-unsigned, p1 :: 64-native-float, p2 :: 64-native-float, p3 :: 64-native-float, p4 :: 64-native-float>>)

  @spec programLocalParameter4fARB(target, index, x, y, z, w) :: :ok when target: enum(), index: integer(), x: float(), y: float(), z: float(), w: float()
  def programLocalParameter4fARB(target, index, x, y, z, w), do: cast(5619, <<target :: 32-native-unsigned, index :: 32-native-unsigned, x :: 32-native-float, y :: 32-native-float, z :: 32-native-float, w :: 32-native-float>>)

  @spec programLocalParameter4fvARB(target, index, params) :: :ok when target: enum(), index: integer(), params: {float(), float(), float(), float()}
  def programLocalParameter4fvARB(target, index, {p1, p2, p3, p4}), do: cast(5620, <<target :: 32-native-unsigned, index :: 32-native-unsigned, p1 :: 32-native-float, p2 :: 32-native-float, p3 :: 32-native-float, p4 :: 32-native-float>>)

  @spec programParameteri(program, pname, value) :: :ok when program: integer(), pname: enum(), value: integer()
  def programParameteri(program, pname, value), do: cast(5776, <<program :: 32-native-unsigned, pname :: 32-native-unsigned, value :: 32-native-signed>>)

  @spec programStringARB(target, format, string) :: :ok when target: enum(), format: enum(), string: charlist()
  def programStringARB(target, format, string) do
    stringLen = length(string)
    cast(5609, <<target :: 32-native-unsigned, format :: 32-native-unsigned, list_to_binary([string, 0]) :: binary, 0 :: size(rem(8 - rem(stringLen + 1, 8), 8))>>)
  end

  @spec programUniform1d(program, location, v0) :: :ok when program: integer(), location: integer(), v0: float()
  def programUniform1d(program, location, v0), do: cast(5789, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 64-native-float>>)

  @spec programUniform1dv(program, location, value) :: :ok when program: integer(), location: integer(), value: [float()]
  def programUniform1dv(program, location, value) do
    valueLen = length(value)
    cast(5790, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, 0 :: 32, (for c <- value do
      <<c :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniform1f(program, location, v0) :: :ok when program: integer(), location: integer(), v0: float()
  def programUniform1f(program, location, v0), do: cast(5787, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-float>>)

  @spec programUniform1fv(program, location, value) :: :ok when program: integer(), location: integer(), value: [float()]
  def programUniform1fv(program, location, value) do
    valueLen = length(value)
    cast(5788, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for c <- value do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(1 + valueLen, 2) * 32)>>)
  end

  @spec programUniform1i(program, location, v0) :: :ok when program: integer(), location: integer(), v0: integer()
  def programUniform1i(program, location, v0), do: cast(5785, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-signed>>)

  @spec programUniform1iv(program, location, value) :: :ok when program: integer(), location: integer(), value: [integer()]
  def programUniform1iv(program, location, value) do
    valueLen = length(value)
    cast(5786, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for c <- value do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + valueLen, 2) * 32)>>)
  end

  @spec programUniform1ui(program, location, v0) :: :ok when program: integer(), location: integer(), v0: integer()
  def programUniform1ui(program, location, v0), do: cast(5791, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-unsigned>>)

  @spec programUniform1uiv(program, location, value) :: :ok when program: integer(), location: integer(), value: [integer()]
  def programUniform1uiv(program, location, value) do
    valueLen = length(value)
    cast(5792, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for c <- value do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + valueLen, 2) * 32)>>)
  end

  @spec programUniform2d(program, location, v0, v1) :: :ok when program: integer(), location: integer(), v0: float(), v1: float()
  def programUniform2d(program, location, v0, v1), do: cast(5797, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 64-native-float, v1 :: 64-native-float>>)

  @spec programUniform2dv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{float(), float()}]
  def programUniform2dv(program, location, value) do
    valueLen = length(value)
    cast(5798, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniform2f(program, location, v0, v1) :: :ok when program: integer(), location: integer(), v0: float(), v1: float()
  def programUniform2f(program, location, v0, v1), do: cast(5795, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-float, v1 :: 32-native-float>>)

  @spec programUniform2fv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{float(), float()}]
  def programUniform2fv(program, location, value) do
    valueLen = length(value)
    cast(5796, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec programUniform2i(program, location, v0, v1) :: :ok when program: integer(), location: integer(), v0: integer(), v1: integer()
  def programUniform2i(program, location, v0, v1), do: cast(5793, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-signed, v1 :: 32-native-signed>>)

  @spec programUniform2iv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{integer(), integer()}]
  def programUniform2iv(program, location, value) do
    valueLen = length(value)
    cast(5794, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2} <- value do
      <<v1 :: 32-native-signed, v2 :: 32-native-signed>>
    end) :: binary>>)
  end

  @spec programUniform2ui(program, location, v0, v1) :: :ok when program: integer(), location: integer(), v0: integer(), v1: integer()
  def programUniform2ui(program, location, v0, v1), do: cast(5799, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-unsigned, v1 :: 32-native-unsigned>>)

  @spec programUniform2uiv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{integer(), integer()}]
  def programUniform2uiv(program, location, value) do
    valueLen = length(value)
    cast(5800, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2} <- value do
      <<v1 :: 32-native-unsigned, v2 :: 32-native-unsigned>>
    end) :: binary>>)
  end

  @spec programUniform3d(program, location, v0, v1, v2) :: :ok when program: integer(), location: integer(), v0: float(), v1: float(), v2: float()
  def programUniform3d(program, location, v0, v1, v2), do: cast(5805, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 64-native-float, v1 :: 64-native-float, v2 :: 64-native-float>>)

  @spec programUniform3dv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{float(), float(), float()}]
  def programUniform3dv(program, location, value) do
    valueLen = length(value)
    cast(5806, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniform3f(program, location, v0, v1, v2) :: :ok when program: integer(), location: integer(), v0: float(), v1: float(), v2: float()
  def programUniform3f(program, location, v0, v1, v2), do: cast(5803, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-float, v1 :: 32-native-float, v2 :: 32-native-float>>)

  @spec programUniform3fv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{float(), float(), float()}]
  def programUniform3fv(program, location, value) do
    valueLen = length(value)
    cast(5804, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec programUniform3i(program, location, v0, v1, v2) :: :ok when program: integer(), location: integer(), v0: integer(), v1: integer(), v2: integer()
  def programUniform3i(program, location, v0, v1, v2), do: cast(5801, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-signed, v1 :: 32-native-signed, v2 :: 32-native-signed>>)

  @spec programUniform3iv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{integer(), integer(), integer()}]
  def programUniform3iv(program, location, value) do
    valueLen = length(value)
    cast(5802, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3} <- value do
      <<v1 :: 32-native-signed, v2 :: 32-native-signed, v3 :: 32-native-signed>>
    end) :: binary>>)
  end

  @spec programUniform3ui(program, location, v0, v1, v2) :: :ok when program: integer(), location: integer(), v0: integer(), v1: integer(), v2: integer()
  def programUniform3ui(program, location, v0, v1, v2), do: cast(5807, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-unsigned, v1 :: 32-native-unsigned, v2 :: 32-native-unsigned>>)

  @spec programUniform3uiv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{integer(), integer(), integer()}]
  def programUniform3uiv(program, location, value) do
    valueLen = length(value)
    cast(5808, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3} <- value do
      <<v1 :: 32-native-unsigned, v2 :: 32-native-unsigned, v3 :: 32-native-unsigned>>
    end) :: binary>>)
  end

  @spec programUniform4d(program, location, v0, v1, v2, v3) :: :ok when program: integer(), location: integer(), v0: float(), v1: float(), v2: float(), v3: float()
  def programUniform4d(program, location, v0, v1, v2, v3), do: cast(5813, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 64-native-float, v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float>>)

  @spec programUniform4dv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{float(), float(), float(), float()}]
  def programUniform4dv(program, location, value) do
    valueLen = length(value)
    cast(5814, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniform4f(program, location, v0, v1, v2, v3) :: :ok when program: integer(), location: integer(), v0: float(), v1: float(), v2: float(), v3: float()
  def programUniform4f(program, location, v0, v1, v2, v3), do: cast(5811, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-float, v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float>>)

  @spec programUniform4fv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{float(), float(), float(), float()}]
  def programUniform4fv(program, location, value) do
    valueLen = length(value)
    cast(5812, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec programUniform4i(program, location, v0, v1, v2, v3) :: :ok when program: integer(), location: integer(), v0: integer(), v1: integer(), v2: integer(), v3: integer()
  def programUniform4i(program, location, v0, v1, v2, v3), do: cast(5809, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-signed, v1 :: 32-native-signed, v2 :: 32-native-signed, v3 :: 32-native-signed>>)

  @spec programUniform4iv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{integer(), integer(), integer(), integer()}]
  def programUniform4iv(program, location, value) do
    valueLen = length(value)
    cast(5810, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 32-native-signed, v2 :: 32-native-signed, v3 :: 32-native-signed, v4 :: 32-native-signed>>
    end) :: binary>>)
  end

  @spec programUniform4ui(program, location, v0, v1, v2, v3) :: :ok when program: integer(), location: integer(), v0: integer(), v1: integer(), v2: integer(), v3: integer()
  def programUniform4ui(program, location, v0, v1, v2, v3), do: cast(5815, <<program :: 32-native-unsigned, location :: 32-native-signed, v0 :: 32-native-unsigned, v1 :: 32-native-unsigned, v2 :: 32-native-unsigned, v3 :: 32-native-unsigned>>)

  @spec programUniform4uiv(program, location, value) :: :ok when program: integer(), location: integer(), value: [{integer(), integer(), integer(), integer()}]
  def programUniform4uiv(program, location, value) do
    valueLen = length(value)
    cast(5816, <<program :: 32-native-unsigned, location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 32-native-unsigned, v2 :: 32-native-unsigned, v3 :: 32-native-unsigned, v4 :: 32-native-unsigned>>
    end) :: binary>>)
  end

  @spec programUniformMatrix2dv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float()}]
  def programUniformMatrix2dv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5820, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 56, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix2fv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float()}]
  def programUniformMatrix2fv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5817, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix2x3dv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix2x3dv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5829, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 56, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix2x3fv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix2x3fv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5823, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix2x4dv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix2x4dv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5831, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 56, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix2x4fv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix2x4fv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5825, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix3dv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix3dv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5821, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 56, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float, v9 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix3fv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix3fv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5818, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float, v9 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix3x2dv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix3x2dv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5830, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 56, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix3x2fv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix3x2fv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5824, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix3x4dv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix3x4dv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5833, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 56, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float, v9 :: 64-native-float, v10 :: 64-native-float, v11 :: 64-native-float, v12 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix3x4fv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix3x4fv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5827, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float, v9 :: 32-native-float, v10 :: 32-native-float, v11 :: 32-native-float, v12 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix4dv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix4dv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5822, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 56, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float, v9 :: 64-native-float, v10 :: 64-native-float, v11 :: 64-native-float, v12 :: 64-native-float, v13 :: 64-native-float, v14 :: 64-native-float, v15 :: 64-native-float, v16 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix4fv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix4fv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5819, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float, v9 :: 32-native-float, v10 :: 32-native-float, v11 :: 32-native-float, v12 :: 32-native-float, v13 :: 32-native-float, v14 :: 32-native-float, v15 :: 32-native-float, v16 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix4x2dv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix4x2dv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5832, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 56, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix4x2fv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix4x2fv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5826, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix4x3dv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix4x3dv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5834, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 56, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float, v9 :: 64-native-float, v10 :: 64-native-float, v11 :: 64-native-float, v12 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec programUniformMatrix4x3fv(program, location, transpose, value) :: :ok when program: integer(), location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def programUniformMatrix4x3fv(program, location, transpose, value) do
    valueLen = length(value)
    cast(5828, <<program :: 32-native-unsigned, location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float, v9 :: 32-native-float, v10 :: 32-native-float, v11 :: 32-native-float, v12 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec provokingVertex(mode) :: :ok when mode: enum()
  def provokingVertex(mode), do: cast(5690, <<mode :: 32-native-unsigned>>)

  @spec pushAttrib(mask) :: :ok when mask: integer()
  def pushAttrib(mask), do: cast(5069, <<mask :: 32-native-unsigned>>)

  @spec pushClientAttrib(mask) :: :ok when mask: integer()
  def pushClientAttrib(mask), do: cast(5071, <<mask :: 32-native-unsigned>>)

  @spec pushMatrix() :: :ok
  def pushMatrix(), do: cast(5089, <<>>)

  @spec pushName(name) :: :ok when name: integer()
  def pushName(name), do: cast(5313, <<name :: 32-native-unsigned>>)

  @spec queryCounter(id, target) :: :ok when id: integer(), target: enum()
  def queryCounter(id, target), do: cast(5724, <<id :: 32-native-unsigned, target :: 32-native-unsigned>>)

  @spec rasterPos2d(x, y) :: :ok when x: float(), y: float()
  def rasterPos2d(x, y), do: cast(5166, <<x :: 64-native-float, y :: 64-native-float>>)

  @spec rasterPos2dv(v) :: :ok when v: {x :: float(), y :: float()}
  def rasterPos2dv({x, y}), do: rasterPos2d(x, y)

  @spec rasterPos2f(x, y) :: :ok when x: float(), y: float()
  def rasterPos2f(x, y), do: cast(5167, <<x :: 32-native-float, y :: 32-native-float>>)

  @spec rasterPos2fv(v) :: :ok when v: {x :: float(), y :: float()}
  def rasterPos2fv({x, y}), do: rasterPos2f(x, y)

  @spec rasterPos2i(x, y) :: :ok when x: integer(), y: integer()
  def rasterPos2i(x, y), do: cast(5168, <<x :: 32-native-signed, y :: 32-native-signed>>)

  @spec rasterPos2iv(v) :: :ok when v: {x :: integer(), y :: integer()}
  def rasterPos2iv({x, y}), do: rasterPos2i(x, y)

  @spec rasterPos2s(x, y) :: :ok when x: integer(), y: integer()
  def rasterPos2s(x, y), do: cast(5169, <<x :: 16-native-signed, y :: 16-native-signed>>)

  @spec rasterPos2sv(v) :: :ok when v: {x :: integer(), y :: integer()}
  def rasterPos2sv({x, y}), do: rasterPos2s(x, y)

  @spec rasterPos3d(x, y, z) :: :ok when x: float(), y: float(), z: float()
  def rasterPos3d(x, y, z), do: cast(5170, <<x :: 64-native-float, y :: 64-native-float, z :: 64-native-float>>)

  @spec rasterPos3dv(v) :: :ok when v: {x :: float(), y :: float(), z :: float()}
  def rasterPos3dv({x, y, z}), do: rasterPos3d(x, y, z)

  @spec rasterPos3f(x, y, z) :: :ok when x: float(), y: float(), z: float()
  def rasterPos3f(x, y, z), do: cast(5171, <<x :: 32-native-float, y :: 32-native-float, z :: 32-native-float>>)

  @spec rasterPos3fv(v) :: :ok when v: {x :: float(), y :: float(), z :: float()}
  def rasterPos3fv({x, y, z}), do: rasterPos3f(x, y, z)

  @spec rasterPos3i(x, y, z) :: :ok when x: integer(), y: integer(), z: integer()
  def rasterPos3i(x, y, z), do: cast(5172, <<x :: 32-native-signed, y :: 32-native-signed, z :: 32-native-signed>>)

  @spec rasterPos3iv(v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer()}
  def rasterPos3iv({x, y, z}), do: rasterPos3i(x, y, z)

  @spec rasterPos3s(x, y, z) :: :ok when x: integer(), y: integer(), z: integer()
  def rasterPos3s(x, y, z), do: cast(5173, <<x :: 16-native-signed, y :: 16-native-signed, z :: 16-native-signed>>)

  @spec rasterPos3sv(v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer()}
  def rasterPos3sv({x, y, z}), do: rasterPos3s(x, y, z)

  @spec rasterPos4d(x, y, z, w) :: :ok when x: float(), y: float(), z: float(), w: float()
  def rasterPos4d(x, y, z, w), do: cast(5174, <<x :: 64-native-float, y :: 64-native-float, z :: 64-native-float, w :: 64-native-float>>)

  @spec rasterPos4dv(v) :: :ok when v: {x :: float(), y :: float(), z :: float(), w :: float()}
  def rasterPos4dv({x, y, z, w}), do: rasterPos4d(x, y, z, w)

  @spec rasterPos4f(x, y, z, w) :: :ok when x: float(), y: float(), z: float(), w: float()
  def rasterPos4f(x, y, z, w), do: cast(5175, <<x :: 32-native-float, y :: 32-native-float, z :: 32-native-float, w :: 32-native-float>>)

  @spec rasterPos4fv(v) :: :ok when v: {x :: float(), y :: float(), z :: float(), w :: float()}
  def rasterPos4fv({x, y, z, w}), do: rasterPos4f(x, y, z, w)

  @spec rasterPos4i(x, y, z, w) :: :ok when x: integer(), y: integer(), z: integer(), w: integer()
  def rasterPos4i(x, y, z, w), do: cast(5176, <<x :: 32-native-signed, y :: 32-native-signed, z :: 32-native-signed, w :: 32-native-signed>>)

  @spec rasterPos4iv(v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer(), w :: integer()}
  def rasterPos4iv({x, y, z, w}), do: rasterPos4i(x, y, z, w)

  @spec rasterPos4s(x, y, z, w) :: :ok when x: integer(), y: integer(), z: integer(), w: integer()
  def rasterPos4s(x, y, z, w), do: cast(5177, <<x :: 16-native-signed, y :: 16-native-signed, z :: 16-native-signed, w :: 16-native-signed>>)

  @spec rasterPos4sv(v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer(), w :: integer()}
  def rasterPos4sv({x, y, z, w}), do: rasterPos4s(x, y, z, w)

  @spec readBuffer(mode) :: :ok when mode: enum()
  def readBuffer(mode), do: cast(5059, <<mode :: 32-native-unsigned>>)

  @spec readPixels(x, y, width, height, format, type, pixels) :: :ok when x: integer(), y: integer(), width: integer(), height: integer(), format: enum(), type: enum(), pixels: mem()
  def readPixels(x, y, width, height, format, type, pixels) do
    send_bin(pixels)
    call(5235, <<x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec rectd(x1, y1, x2, y2) :: :ok when x1: float(), y1: float(), x2: float(), y2: float()
  def rectd(x1, y1, x2, y2), do: cast(5178, <<x1 :: 64-native-float, y1 :: 64-native-float, x2 :: 64-native-float, y2 :: 64-native-float>>)

  @spec rectdv(v1, v2) :: :ok when v1: {float(), float()}, v2: {float(), float()}
  def rectdv({v1, v2}, {^v1, ^v2}), do: cast(5182, <<v1 :: 64-native-float, v2 :: 64-native-float, v1 :: 64-native-float, v2 :: 64-native-float>>)

  @spec rectf(x1, y1, x2, y2) :: :ok when x1: float(), y1: float(), x2: float(), y2: float()
  def rectf(x1, y1, x2, y2), do: cast(5179, <<x1 :: 32-native-float, y1 :: 32-native-float, x2 :: 32-native-float, y2 :: 32-native-float>>)

  @spec rectfv(v1, v2) :: :ok when v1: {float(), float()}, v2: {float(), float()}
  def rectfv({v1, v2}, {^v1, ^v2}), do: cast(5183, <<v1 :: 32-native-float, v2 :: 32-native-float, v1 :: 32-native-float, v2 :: 32-native-float>>)

  @spec recti(x1, y1, x2, y2) :: :ok when x1: integer(), y1: integer(), x2: integer(), y2: integer()
  def recti(x1, y1, x2, y2), do: cast(5180, <<x1 :: 32-native-signed, y1 :: 32-native-signed, x2 :: 32-native-signed, y2 :: 32-native-signed>>)

  @spec rectiv(v1, v2) :: :ok when v1: {integer(), integer()}, v2: {integer(), integer()}
  def rectiv({v1, v2}, {^v1, ^v2}), do: cast(5184, <<v1 :: 32-native-signed, v2 :: 32-native-signed, v1 :: 32-native-signed, v2 :: 32-native-signed>>)

  @spec rects(x1, y1, x2, y2) :: :ok when x1: integer(), y1: integer(), x2: integer(), y2: integer()
  def rects(x1, y1, x2, y2), do: cast(5181, <<x1 :: 16-native-signed, y1 :: 16-native-signed, x2 :: 16-native-signed, y2 :: 16-native-signed>>)

  @spec rectsv(v1, v2) :: :ok when v1: {integer(), integer()}, v2: {integer(), integer()}
  def rectsv({v1, v2}, {^v1, ^v2}), do: cast(5185, <<v1 :: 16-native-signed, v2 :: 16-native-signed, v1 :: 16-native-signed, v2 :: 16-native-signed>>)

  @spec releaseShaderCompiler() :: :ok
  def releaseShaderCompiler(), do: cast(5769, <<>>)

  @spec renderMode(mode) :: integer() when mode: enum()
  def renderMode(mode), do: call(5073, <<mode :: 32-native-unsigned>>)

  @spec renderbufferStorage(target, internalformat, width, height) :: :ok when target: enum(), internalformat: enum(), width: integer(), height: integer()
  def renderbufferStorage(target, internalformat, width, height), do: cast(5654, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed>>)

  @spec renderbufferStorageMultisample(target, samples, internalformat, width, height) :: :ok when target: enum(), samples: integer(), internalformat: enum(), width: integer(), height: integer()
  def renderbufferStorageMultisample(target, samples, internalformat, width, height), do: cast(5668, <<target :: 32-native-unsigned, samples :: 32-native-signed, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed>>)

  @spec resetHistogram(target) :: :ok when target: enum()
  def resetHistogram(target), do: cast(5356, <<target :: 32-native-unsigned>>)

  @spec resetMinmax(target) :: :ok when target: enum()
  def resetMinmax(target), do: cast(5357, <<target :: 32-native-unsigned>>)

  @spec resumeTransformFeedback() :: :ok
  def resumeTransformFeedback(), do: cast(5763, <<>>)

  @spec rotated(angle, x, y, z) :: :ok when angle: float(), x: float(), y: float(), z: float()
  def rotated(angle, x, y, z), do: cast(5096, <<angle :: 64-native-float, x :: 64-native-float, y :: 64-native-float, z :: 64-native-float>>)

  @spec rotatef(angle, x, y, z) :: :ok when angle: float(), x: float(), y: float(), z: float()
  def rotatef(angle, x, y, z), do: cast(5097, <<angle :: 32-native-float, x :: 32-native-float, y :: 32-native-float, z :: 32-native-float>>)

  @spec sampleCoverage(value, invert) :: :ok when value: clamp(), invert: (0 | 1)
  def sampleCoverage(value, invert), do: cast(5359, <<value :: 32-native-float, invert :: 8-native-unsigned>>)

  @spec sampleMaski(index, mask) :: :ok when index: integer(), mask: integer()
  def sampleMaski(index, mask), do: cast(5701, <<index :: 32-native-unsigned, mask :: 32-native-unsigned>>)

  @spec samplerParameterIiv(sampler, pname, param) :: :ok when sampler: integer(), pname: enum(), param: [integer()]
  def samplerParameterIiv(sampler, pname, param) do
    paramLen = length(param)
    cast(5718, <<sampler :: 32-native-unsigned, pname :: 32-native-unsigned, paramLen :: 32-native-unsigned, (for c <- param do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + paramLen, 2) * 32)>>)
  end

  @spec samplerParameterIuiv(sampler, pname, param) :: :ok when sampler: integer(), pname: enum(), param: [integer()]
  def samplerParameterIuiv(sampler, pname, param) do
    paramLen = length(param)
    cast(5719, <<sampler :: 32-native-unsigned, pname :: 32-native-unsigned, paramLen :: 32-native-unsigned, (for c <- param do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + paramLen, 2) * 32)>>)
  end

  @spec samplerParameterf(sampler, pname, param) :: :ok when sampler: integer(), pname: enum(), param: float()
  def samplerParameterf(sampler, pname, param), do: cast(5716, <<sampler :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-float>>)

  @spec samplerParameterfv(sampler, pname, param) :: :ok when sampler: integer(), pname: enum(), param: [float()]
  def samplerParameterfv(sampler, pname, param) do
    paramLen = length(param)
    cast(5717, <<sampler :: 32-native-unsigned, pname :: 32-native-unsigned, paramLen :: 32-native-unsigned, (for c <- param do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(1 + paramLen, 2) * 32)>>)
  end

  @spec samplerParameteri(sampler, pname, param) :: :ok when sampler: integer(), pname: enum(), param: integer()
  def samplerParameteri(sampler, pname, param), do: cast(5714, <<sampler :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-signed>>)

  @spec samplerParameteriv(sampler, pname, param) :: :ok when sampler: integer(), pname: enum(), param: [integer()]
  def samplerParameteriv(sampler, pname, param) do
    paramLen = length(param)
    cast(5715, <<sampler :: 32-native-unsigned, pname :: 32-native-unsigned, paramLen :: 32-native-unsigned, (for c <- param do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + paramLen, 2) * 32)>>)
  end

  @spec scaled(x, y, z) :: :ok when x: float(), y: float(), z: float()
  def scaled(x, y, z), do: cast(5098, <<x :: 64-native-float, y :: 64-native-float, z :: 64-native-float>>)

  @spec scalef(x, y, z) :: :ok when x: float(), y: float(), z: float()
  def scalef(x, y, z), do: cast(5099, <<x :: 32-native-float, y :: 32-native-float, z :: 32-native-float>>)

  @spec scissor(x, y, width, height) :: :ok when x: integer(), y: integer(), width: integer(), height: integer()
  def scissor(x, y, width, height), do: cast(5055, <<x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed>>)

  @spec scissorArrayv(first, v) :: :ok when first: integer(), v: [{integer(), integer(), integer(), integer()}]
  def scissorArrayv(first, v) do
    vLen = length(v)
    cast(5847, <<first :: 32-native-unsigned, vLen :: 32-native-unsigned, (for {v1, v2, v3, v4} <- v do
      <<v1 :: 32-native-signed, v2 :: 32-native-signed, v3 :: 32-native-signed, v4 :: 32-native-signed>>
    end) :: binary>>)
  end

  @spec scissorIndexed(index, left, bottom, width, height) :: :ok when index: integer(), left: integer(), bottom: integer(), width: integer(), height: integer()
  def scissorIndexed(index, left, bottom, width, height), do: cast(5848, <<index :: 32-native-unsigned, left :: 32-native-signed, bottom :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed>>)

  @spec scissorIndexedv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def scissorIndexedv(index, {v1, v2, v3, v4}), do: cast(5849, <<index :: 32-native-unsigned, v1 :: 32-native-signed, v2 :: 32-native-signed, v3 :: 32-native-signed, v4 :: 32-native-signed>>)

  @spec secondaryColor3b(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def secondaryColor3b(red, green, blue), do: cast(5405, <<red :: 8-native-signed, green :: 8-native-signed, blue :: 8-native-signed>>)

  @spec secondaryColor3bv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def secondaryColor3bv({red, green, blue}), do: secondaryColor3b(red, green, blue)

  @spec secondaryColor3d(red, green, blue) :: :ok when red: float(), green: float(), blue: float()
  def secondaryColor3d(red, green, blue), do: cast(5406, <<red :: 64-native-float, green :: 64-native-float, blue :: 64-native-float>>)

  @spec secondaryColor3dv(v) :: :ok when v: {red :: float(), green :: float(), blue :: float()}
  def secondaryColor3dv({red, green, blue}), do: secondaryColor3d(red, green, blue)

  @spec secondaryColor3f(red, green, blue) :: :ok when red: float(), green: float(), blue: float()
  def secondaryColor3f(red, green, blue), do: cast(5407, <<red :: 32-native-float, green :: 32-native-float, blue :: 32-native-float>>)

  @spec secondaryColor3fv(v) :: :ok when v: {red :: float(), green :: float(), blue :: float()}
  def secondaryColor3fv({red, green, blue}), do: secondaryColor3f(red, green, blue)

  @spec secondaryColor3i(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def secondaryColor3i(red, green, blue), do: cast(5408, <<red :: 32-native-signed, green :: 32-native-signed, blue :: 32-native-signed>>)

  @spec secondaryColor3iv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def secondaryColor3iv({red, green, blue}), do: secondaryColor3i(red, green, blue)

  @spec secondaryColor3s(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def secondaryColor3s(red, green, blue), do: cast(5409, <<red :: 16-native-signed, green :: 16-native-signed, blue :: 16-native-signed>>)

  @spec secondaryColor3sv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def secondaryColor3sv({red, green, blue}), do: secondaryColor3s(red, green, blue)

  @spec secondaryColor3ub(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def secondaryColor3ub(red, green, blue), do: cast(5410, <<red :: 8-native-unsigned, green :: 8-native-unsigned, blue :: 8-native-unsigned>>)

  @spec secondaryColor3ubv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def secondaryColor3ubv({red, green, blue}), do: secondaryColor3ub(red, green, blue)

  @spec secondaryColor3ui(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def secondaryColor3ui(red, green, blue), do: cast(5411, <<red :: 32-native-unsigned, green :: 32-native-unsigned, blue :: 32-native-unsigned>>)

  @spec secondaryColor3uiv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def secondaryColor3uiv({red, green, blue}), do: secondaryColor3ui(red, green, blue)

  @spec secondaryColor3us(red, green, blue) :: :ok when red: integer(), green: integer(), blue: integer()
  def secondaryColor3us(red, green, blue), do: cast(5412, <<red :: 16-native-unsigned, green :: 16-native-unsigned, blue :: 16-native-unsigned>>)

  @spec secondaryColor3usv(v) :: :ok when v: {red :: integer(), green :: integer(), blue :: integer()}
  def secondaryColor3usv({red, green, blue}), do: secondaryColor3us(red, green, blue)

  @spec secondaryColorPointer(size, type, stride, pointer) :: :ok when size: integer(), type: enum(), stride: integer(), pointer: (offset() | mem())
  def secondaryColorPointer(size, type, stride, pointer) when is_integer(pointer), do: cast(5413, <<size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed, pointer :: 32-native-unsigned>>)

  def secondaryColorPointer(size, type, stride, pointer) do
    send_bin(pointer)
    cast(5414, <<size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed>>)
  end

  @spec selectBuffer(size, buffer) :: :ok when size: integer(), buffer: mem()
  def selectBuffer(size, buffer) do
    send_bin(buffer)
    call(5310, <<size :: 32-native-signed>>)
  end

  def send_bin(bin) when is_binary(bin) do
    port = get(:opengl_port)
    :erlang.port_command(port, bin)
  end

  def send_bin(tuple) when is_tuple(tuple) do
    port = get(:opengl_port)
    case element(2, tuple) do
      bin when is_binary(bin) ->
        :erlang.port_command(port, bin)
    end
  end

  @spec separableFilter2D(target, internalformat, width, height, format, type, row, column) :: :ok when target: enum(), internalformat: enum(), width: integer(), height: integer(), format: enum(), type: enum(), row: (offset() | mem()), column: (offset() | mem())
  def separableFilter2D(target, internalformat, width, height, format, type, row, column) when is_integer(row) and is_integer(column), do: cast(5346, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, row :: 32-native-unsigned, column :: 32-native-unsigned>>)

  def separableFilter2D(target, internalformat, width, height, format, type, row, column) do
    send_bin(row)
    send_bin(column)
    cast(5347, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec shadeModel(mode) :: :ok when mode: enum()
  def shadeModel(mode), do: cast(5204, <<mode :: 32-native-unsigned>>)

  @spec shaderBinary(shaders, binaryformat, binary) :: :ok when shaders: [integer()], binaryformat: enum(), binary: binary()
  def shaderBinary(shaders, binaryformat, binary) do
    shadersLen = length(shaders)
    send_bin(binary)
    cast(5770, <<shadersLen :: 32-native-unsigned, (for c <- shaders do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + shadersLen, 2) * 32), binaryformat :: 32-native-unsigned>>)
  end

  @spec shaderSource(shader, string) :: :ok when shader: integer(), string: iolist()
  def shaderSource(shader, string) do
    stringTemp = list_to_binary((for str <- string do
      [str, 0]
    end))
    stringLen = length(string)
    cast(5474, <<shader :: 32-native-unsigned, stringLen :: 32-native-unsigned, size(stringTemp) :: 32-native-unsigned, stringTemp :: binary, 0 :: size(rem(8 - rem(size(stringTemp) + 0, 8), 8))>>)
  end

  @spec shaderSourceARB(shaderObj, string) :: :ok when shaderObj: integer(), string: iolist()
  def shaderSourceARB(shaderObj, string) do
    stringTemp = list_to_binary((for str <- string do
      [str, 0]
    end))
    stringLen = length(string)
    cast(5631, <<shaderObj :: 64-native-unsigned, stringLen :: 32-native-unsigned, size(stringTemp) :: 32-native-unsigned, stringTemp :: binary, 0 :: size(rem(8 - rem(size(stringTemp) + 4, 8), 8))>>)
  end

  @spec stencilClearTagEXT(stencilTagBits, stencilClearTag) :: :ok when stencilTagBits: integer(), stencilClearTag: integer()
  def stencilClearTagEXT(stencilTagBits, stencilClearTag), do: cast(5872, <<stencilTagBits :: 32-native-signed, stencilClearTag :: 32-native-unsigned>>)

  @spec stencilFunc(func, ref, mask) :: :ok when func: enum(), ref: integer(), mask: integer()
  def stencilFunc(func, ref, mask), do: cast(5239, <<func :: 32-native-unsigned, ref :: 32-native-signed, mask :: 32-native-unsigned>>)

  @spec stencilFuncSeparate(face, func, ref, mask) :: :ok when face: enum(), func: enum(), ref: integer(), mask: integer()
  def stencilFuncSeparate(face, func, ref, mask), do: cast(5444, <<face :: 32-native-unsigned, func :: 32-native-unsigned, ref :: 32-native-signed, mask :: 32-native-unsigned>>)

  @spec stencilMask(mask) :: :ok when mask: integer()
  def stencilMask(mask), do: cast(5240, <<mask :: 32-native-unsigned>>)

  @spec stencilMaskSeparate(face, mask) :: :ok when face: enum(), mask: integer()
  def stencilMaskSeparate(face, mask), do: cast(5445, <<face :: 32-native-unsigned, mask :: 32-native-unsigned>>)

  @spec stencilOp(fail, zfail, zpass) :: :ok when fail: enum(), zfail: enum(), zpass: enum()
  def stencilOp(fail, zfail, zpass), do: cast(5241, <<fail :: 32-native-unsigned, zfail :: 32-native-unsigned, zpass :: 32-native-unsigned>>)

  @spec stencilOpSeparate(face, sfail, dpfail, dppass) :: :ok when face: enum(), sfail: enum(), dpfail: enum(), dppass: enum()
  def stencilOpSeparate(face, sfail, dpfail, dppass), do: cast(5443, <<face :: 32-native-unsigned, sfail :: 32-native-unsigned, dpfail :: 32-native-unsigned, dppass :: 32-native-unsigned>>)

  @spec texBuffer(target, internalformat, buffer) :: :ok when target: enum(), internalformat: enum(), buffer: integer()
  def texBuffer(target, internalformat, buffer), do: cast(5581, <<target :: 32-native-unsigned, internalformat :: 32-native-unsigned, buffer :: 32-native-unsigned>>)

  @spec texCoord1d(s) :: :ok when s: float()
  def texCoord1d(s), do: cast(5150, <<s :: 64-native-float>>)

  @spec texCoord1dv(v) :: :ok when v: {s :: float()}
  def texCoord1dv({s}), do: texCoord1d(s)

  @spec texCoord1f(s) :: :ok when s: float()
  def texCoord1f(s), do: cast(5151, <<s :: 32-native-float>>)

  @spec texCoord1fv(v) :: :ok when v: {s :: float()}
  def texCoord1fv({s}), do: texCoord1f(s)

  @spec texCoord1i(s) :: :ok when s: integer()
  def texCoord1i(s), do: cast(5152, <<s :: 32-native-signed>>)

  @spec texCoord1iv(v) :: :ok when v: {s :: integer()}
  def texCoord1iv({s}), do: texCoord1i(s)

  @spec texCoord1s(s) :: :ok when s: integer()
  def texCoord1s(s), do: cast(5153, <<s :: 16-native-signed>>)

  @spec texCoord1sv(v) :: :ok when v: {s :: integer()}
  def texCoord1sv({s}), do: texCoord1s(s)

  @spec texCoord2d(s, t) :: :ok when s: float(), t: float()
  def texCoord2d(s, t), do: cast(5154, <<s :: 64-native-float, t :: 64-native-float>>)

  @spec texCoord2dv(v) :: :ok when v: {s :: float(), t :: float()}
  def texCoord2dv({s, t}), do: texCoord2d(s, t)

  @spec texCoord2f(s, t) :: :ok when s: float(), t: float()
  def texCoord2f(s, t), do: cast(5155, <<s :: 32-native-float, t :: 32-native-float>>)

  @spec texCoord2fv(v) :: :ok when v: {s :: float(), t :: float()}
  def texCoord2fv({s, t}), do: texCoord2f(s, t)

  @spec texCoord2i(s, t) :: :ok when s: integer(), t: integer()
  def texCoord2i(s, t), do: cast(5156, <<s :: 32-native-signed, t :: 32-native-signed>>)

  @spec texCoord2iv(v) :: :ok when v: {s :: integer(), t :: integer()}
  def texCoord2iv({s, t}), do: texCoord2i(s, t)

  @spec texCoord2s(s, t) :: :ok when s: integer(), t: integer()
  def texCoord2s(s, t), do: cast(5157, <<s :: 16-native-signed, t :: 16-native-signed>>)

  @spec texCoord2sv(v) :: :ok when v: {s :: integer(), t :: integer()}
  def texCoord2sv({s, t}), do: texCoord2s(s, t)

  @spec texCoord3d(s, t, r) :: :ok when s: float(), t: float(), r: float()
  def texCoord3d(s, t, r), do: cast(5158, <<s :: 64-native-float, t :: 64-native-float, r :: 64-native-float>>)

  @spec texCoord3dv(v) :: :ok when v: {s :: float(), t :: float(), r :: float()}
  def texCoord3dv({s, t, r}), do: texCoord3d(s, t, r)

  @spec texCoord3f(s, t, r) :: :ok when s: float(), t: float(), r: float()
  def texCoord3f(s, t, r), do: cast(5159, <<s :: 32-native-float, t :: 32-native-float, r :: 32-native-float>>)

  @spec texCoord3fv(v) :: :ok when v: {s :: float(), t :: float(), r :: float()}
  def texCoord3fv({s, t, r}), do: texCoord3f(s, t, r)

  @spec texCoord3i(s, t, r) :: :ok when s: integer(), t: integer(), r: integer()
  def texCoord3i(s, t, r), do: cast(5160, <<s :: 32-native-signed, t :: 32-native-signed, r :: 32-native-signed>>)

  @spec texCoord3iv(v) :: :ok when v: {s :: integer(), t :: integer(), r :: integer()}
  def texCoord3iv({s, t, r}), do: texCoord3i(s, t, r)

  @spec texCoord3s(s, t, r) :: :ok when s: integer(), t: integer(), r: integer()
  def texCoord3s(s, t, r), do: cast(5161, <<s :: 16-native-signed, t :: 16-native-signed, r :: 16-native-signed>>)

  @spec texCoord3sv(v) :: :ok when v: {s :: integer(), t :: integer(), r :: integer()}
  def texCoord3sv({s, t, r}), do: texCoord3s(s, t, r)

  @spec texCoord4d(s, t, r, q) :: :ok when s: float(), t: float(), r: float(), q: float()
  def texCoord4d(s, t, r, q), do: cast(5162, <<s :: 64-native-float, t :: 64-native-float, r :: 64-native-float, q :: 64-native-float>>)

  @spec texCoord4dv(v) :: :ok when v: {s :: float(), t :: float(), r :: float(), q :: float()}
  def texCoord4dv({s, t, r, q}), do: texCoord4d(s, t, r, q)

  @spec texCoord4f(s, t, r, q) :: :ok when s: float(), t: float(), r: float(), q: float()
  def texCoord4f(s, t, r, q), do: cast(5163, <<s :: 32-native-float, t :: 32-native-float, r :: 32-native-float, q :: 32-native-float>>)

  @spec texCoord4fv(v) :: :ok when v: {s :: float(), t :: float(), r :: float(), q :: float()}
  def texCoord4fv({s, t, r, q}), do: texCoord4f(s, t, r, q)

  @spec texCoord4i(s, t, r, q) :: :ok when s: integer(), t: integer(), r: integer(), q: integer()
  def texCoord4i(s, t, r, q), do: cast(5164, <<s :: 32-native-signed, t :: 32-native-signed, r :: 32-native-signed, q :: 32-native-signed>>)

  @spec texCoord4iv(v) :: :ok when v: {s :: integer(), t :: integer(), r :: integer(), q :: integer()}
  def texCoord4iv({s, t, r, q}), do: texCoord4i(s, t, r, q)

  @spec texCoord4s(s, t, r, q) :: :ok when s: integer(), t: integer(), r: integer(), q: integer()
  def texCoord4s(s, t, r, q), do: cast(5165, <<s :: 16-native-signed, t :: 16-native-signed, r :: 16-native-signed, q :: 16-native-signed>>)

  @spec texCoord4sv(v) :: :ok when v: {s :: integer(), t :: integer(), r :: integer(), q :: integer()}
  def texCoord4sv({s, t, r, q}), do: texCoord4s(s, t, r, q)

  @spec texCoordPointer(size, type, stride, ptr) :: :ok when size: integer(), type: enum(), stride: integer(), ptr: (offset() | mem())
  def texCoordPointer(size, type, stride, ptr) when is_integer(ptr), do: cast(5194, <<size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed, ptr :: 32-native-unsigned>>)

  def texCoordPointer(size, type, stride, ptr) do
    send_bin(ptr)
    cast(5195, <<size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed>>)
  end

  @spec texEnvf(target, pname, param) :: :ok when target: enum(), pname: enum(), param: float()
  def texEnvf(target, pname, param), do: cast(5252, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-float>>)

  @spec texEnvfv(target, pname, params) :: :ok when target: enum(), pname: enum(), params: tuple()
  def texEnvfv(target, pname, params) do
    cast(5254, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec texEnvi(target, pname, param) :: :ok when target: enum(), pname: enum(), param: integer()
  def texEnvi(target, pname, param), do: cast(5253, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-signed>>)

  @spec texEnviv(target, pname, params) :: :ok when target: enum(), pname: enum(), params: tuple()
  def texEnviv(target, pname, params) do
    cast(5255, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec texGend(coord, pname, param) :: :ok when coord: enum(), pname: enum(), param: float()
  def texGend(coord, pname, param), do: cast(5243, <<coord :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 64-native-float>>)

  @spec texGendv(coord, pname, params) :: :ok when coord: enum(), pname: enum(), params: tuple()
  def texGendv(coord, pname, params) do
    cast(5246, <<coord :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, 0 :: 32, (for c <- tuple_to_list(params) do
      <<c :: 64-native-float>>
    end) :: binary>>)
  end

  @spec texGenf(coord, pname, param) :: :ok when coord: enum(), pname: enum(), param: float()
  def texGenf(coord, pname, param), do: cast(5244, <<coord :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-float>>)

  @spec texGenfv(coord, pname, params) :: :ok when coord: enum(), pname: enum(), params: tuple()
  def texGenfv(coord, pname, params) do
    cast(5247, <<coord :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec texGeni(coord, pname, param) :: :ok when coord: enum(), pname: enum(), param: integer()
  def texGeni(coord, pname, param), do: cast(5245, <<coord :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-signed>>)

  @spec texGeniv(coord, pname, params) :: :ok when coord: enum(), pname: enum(), params: tuple()
  def texGeniv(coord, pname, params) do
    cast(5248, <<coord :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec texImage1D(target, level, internalFormat, width, border, format, type, pixels) :: :ok when target: enum(), level: integer(), internalFormat: integer(), width: integer(), border: integer(), format: enum(), type: enum(), pixels: (offset() | mem())
  def texImage1D(target, level, internalFormat, width, border, format, type, pixels) when is_integer(pixels), do: cast(5266, <<target :: 32-native-unsigned, level :: 32-native-signed, internalFormat :: 32-native-signed, width :: 32-native-signed, border :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, pixels :: 32-native-unsigned>>)

  def texImage1D(target, level, internalFormat, width, border, format, type, pixels) do
    send_bin(pixels)
    cast(5267, <<target :: 32-native-unsigned, level :: 32-native-signed, internalFormat :: 32-native-signed, width :: 32-native-signed, border :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec texImage2D(target, level, internalFormat, width, height, border, format, type, pixels) :: :ok when target: enum(), level: integer(), internalFormat: integer(), width: integer(), height: integer(), border: integer(), format: enum(), type: enum(), pixels: (offset() | mem())
  def texImage2D(target, level, internalFormat, width, height, border, format, type, pixels) when is_integer(pixels), do: cast(5268, <<target :: 32-native-unsigned, level :: 32-native-signed, internalFormat :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, border :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, pixels :: 32-native-unsigned>>)

  def texImage2D(target, level, internalFormat, width, height, border, format, type, pixels) do
    send_bin(pixels)
    cast(5269, <<target :: 32-native-unsigned, level :: 32-native-signed, internalFormat :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, border :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec texImage2DMultisample(target, samples, internalformat, width, height, fixedsamplelocations) :: :ok when target: enum(), samples: integer(), internalformat: integer(), width: integer(), height: integer(), fixedsamplelocations: (0 | 1)
  def texImage2DMultisample(target, samples, internalformat, width, height, fixedsamplelocations), do: cast(5698, <<target :: 32-native-unsigned, samples :: 32-native-signed, internalformat :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, fixedsamplelocations :: 8-native-unsigned>>)

  @spec texImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels) :: :ok when target: enum(), level: integer(), internalFormat: integer(), width: integer(), height: integer(), depth: integer(), border: integer(), format: enum(), type: enum(), pixels: (offset() | mem())
  def texImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels) when is_integer(pixels), do: cast(5319, <<target :: 32-native-unsigned, level :: 32-native-signed, internalFormat :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, depth :: 32-native-signed, border :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, pixels :: 32-native-unsigned>>)

  def texImage3D(target, level, internalFormat, width, height, depth, border, format, type, pixels) do
    send_bin(pixels)
    cast(5320, <<target :: 32-native-unsigned, level :: 32-native-signed, internalFormat :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, depth :: 32-native-signed, border :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec texImage3DMultisample(target, samples, internalformat, width, height, depth, fixedsamplelocations) :: :ok when target: enum(), samples: integer(), internalformat: integer(), width: integer(), height: integer(), depth: integer(), fixedsamplelocations: (0 | 1)
  def texImage3DMultisample(target, samples, internalformat, width, height, depth, fixedsamplelocations), do: cast(5699, <<target :: 32-native-unsigned, samples :: 32-native-signed, internalformat :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, depth :: 32-native-signed, fixedsamplelocations :: 8-native-unsigned>>)

  @spec texParameterIiv(target, pname, params) :: :ok when target: enum(), pname: enum(), params: tuple()
  def texParameterIiv(target, pname, params) do
    cast(5569, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec texParameterIuiv(target, pname, params) :: :ok when target: enum(), pname: enum(), params: tuple()
  def texParameterIuiv(target, pname, params) do
    cast(5570, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec texParameterf(target, pname, param) :: :ok when target: enum(), pname: enum(), param: float()
  def texParameterf(target, pname, param), do: cast(5258, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-float>>)

  @spec texParameterfv(target, pname, params) :: :ok when target: enum(), pname: enum(), params: tuple()
  def texParameterfv(target, pname, params) do
    cast(5260, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec texParameteri(target, pname, param) :: :ok when target: enum(), pname: enum(), param: integer()
  def texParameteri(target, pname, param), do: cast(5259, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, param :: 32-native-signed>>)

  @spec texParameteriv(target, pname, params) :: :ok when target: enum(), pname: enum(), params: tuple()
  def texParameteriv(target, pname, params) do
    cast(5261, <<target :: 32-native-unsigned, pname :: 32-native-unsigned, size(params) :: 32-native-unsigned, (for c <- tuple_to_list(params) do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + size(params), 2) * 32)>>)
  end

  @spec texStorage1D(target, levels, internalformat, width) :: :ok when target: enum(), levels: integer(), internalformat: enum(), width: integer()
  def texStorage1D(target, levels, internalformat, width), do: cast(5868, <<target :: 32-native-unsigned, levels :: 32-native-signed, internalformat :: 32-native-unsigned, width :: 32-native-signed>>)

  @spec texStorage2D(target, levels, internalformat, width, height) :: :ok when target: enum(), levels: integer(), internalformat: enum(), width: integer(), height: integer()
  def texStorage2D(target, levels, internalformat, width, height), do: cast(5869, <<target :: 32-native-unsigned, levels :: 32-native-signed, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed>>)

  @spec texStorage3D(target, levels, internalformat, width, height, depth) :: :ok when target: enum(), levels: integer(), internalformat: enum(), width: integer(), height: integer(), depth: integer()
  def texStorage3D(target, levels, internalformat, width, height, depth), do: cast(5870, <<target :: 32-native-unsigned, levels :: 32-native-signed, internalformat :: 32-native-unsigned, width :: 32-native-signed, height :: 32-native-signed, depth :: 32-native-signed>>)

  @spec texSubImage1D(target, level, xoffset, width, format, type, pixels) :: :ok when target: enum(), level: integer(), xoffset: integer(), width: integer(), format: enum(), type: enum(), pixels: (offset() | mem())
  def texSubImage1D(target, level, xoffset, width, format, type, pixels) when is_integer(pixels), do: cast(5277, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, width :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, pixels :: 32-native-unsigned>>)

  def texSubImage1D(target, level, xoffset, width, format, type, pixels) do
    send_bin(pixels)
    cast(5278, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, width :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels) :: :ok when target: enum(), level: integer(), xoffset: integer(), yoffset: integer(), width: integer(), height: integer(), format: enum(), type: enum(), pixels: (offset() | mem())
  def texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels) when is_integer(pixels), do: cast(5279, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, yoffset :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, pixels :: 32-native-unsigned>>)

  def texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels) do
    send_bin(pixels)
    cast(5280, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, yoffset :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec texSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels) :: :ok when target: enum(), level: integer(), xoffset: integer(), yoffset: integer(), zoffset: integer(), width: integer(), height: integer(), depth: integer(), format: enum(), type: enum(), pixels: (offset() | mem())
  def texSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels) when is_integer(pixels), do: cast(5321, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, yoffset :: 32-native-signed, zoffset :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, depth :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned, pixels :: 32-native-unsigned>>)

  def texSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels) do
    send_bin(pixels)
    cast(5322, <<target :: 32-native-unsigned, level :: 32-native-signed, xoffset :: 32-native-signed, yoffset :: 32-native-signed, zoffset :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed, depth :: 32-native-signed, format :: 32-native-unsigned, type :: 32-native-unsigned>>)
  end

  @spec transformFeedbackVaryings(program, varyings, bufferMode) :: :ok when program: integer(), varyings: iolist(), bufferMode: enum()
  def transformFeedbackVaryings(program, varyings, bufferMode) do
    varyingsTemp = list_to_binary((for str <- varyings do
      [str, 0]
    end))
    varyingsLen = length(varyings)
    cast(5537, <<program :: 32-native-unsigned, varyingsLen :: 32-native-unsigned, size(varyingsTemp) :: 32-native-unsigned, varyingsTemp :: binary, 0 :: size(rem(8 - rem(size(varyingsTemp) + 0, 8), 8)), bufferMode :: 32-native-unsigned>>)
  end

  @spec translated(x, y, z) :: :ok when x: float(), y: float(), z: float()
  def translated(x, y, z), do: cast(5100, <<x :: 64-native-float, y :: 64-native-float, z :: 64-native-float>>)

  @spec translatef(x, y, z) :: :ok when x: float(), y: float(), z: float()
  def translatef(x, y, z), do: cast(5101, <<x :: 32-native-float, y :: 32-native-float, z :: 32-native-float>>)

  @spec uniform1d(location, x) :: :ok when location: integer(), x: float()
  def uniform1d(location, x), do: cast(5731, <<location :: 32-native-signed, 0 :: 32, x :: 64-native-float>>)

  @spec uniform1dv(location, value) :: :ok when location: integer(), value: [float()]
  def uniform1dv(location, value) do
    valueLen = length(value)
    cast(5735, <<location :: 32-native-signed, 0 :: 32, valueLen :: 32-native-unsigned, 0 :: 32, (for c <- value do
      <<c :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniform1f(location, v0) :: :ok when location: integer(), v0: float()
  def uniform1f(location, v0), do: cast(5476, <<location :: 32-native-signed, v0 :: 32-native-float>>)

  @spec uniform1fv(location, value) :: :ok when location: integer(), value: [float()]
  def uniform1fv(location, value) do
    valueLen = length(value)
    cast(5484, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for c <- value do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(valueLen, 2) * 32)>>)
  end

  @spec uniform1i(location, v0) :: :ok when location: integer(), v0: integer()
  def uniform1i(location, v0), do: cast(5480, <<location :: 32-native-signed, v0 :: 32-native-signed>>)

  @spec uniform1iv(location, value) :: :ok when location: integer(), value: [integer()]
  def uniform1iv(location, value) do
    valueLen = length(value)
    cast(5488, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for c <- value do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(valueLen, 2) * 32)>>)
  end

  @spec uniform1ui(location, v0) :: :ok when location: integer(), v0: integer()
  def uniform1ui(location, v0), do: cast(5561, <<location :: 32-native-signed, v0 :: 32-native-unsigned>>)

  @spec uniform1uiv(location, value) :: :ok when location: integer(), value: [integer()]
  def uniform1uiv(location, value) do
    valueLen = length(value)
    cast(5565, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for c <- value do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(valueLen, 2) * 32)>>)
  end

  @spec uniform2d(location, x, y) :: :ok when location: integer(), x: float(), y: float()
  def uniform2d(location, x, y), do: cast(5732, <<location :: 32-native-signed, 0 :: 32, x :: 64-native-float, y :: 64-native-float>>)

  @spec uniform2dv(location, value) :: :ok when location: integer(), value: [{float(), float()}]
  def uniform2dv(location, value) do
    valueLen = length(value)
    cast(5736, <<location :: 32-native-signed, 0 :: 32, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniform2f(location, v0, v1) :: :ok when location: integer(), v0: float(), v1: float()
  def uniform2f(location, v0, v1), do: cast(5477, <<location :: 32-native-signed, v0 :: 32-native-float, v1 :: 32-native-float>>)

  @spec uniform2fv(location, value) :: :ok when location: integer(), value: [{float(), float()}]
  def uniform2fv(location, value) do
    valueLen = length(value)
    cast(5485, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniform2i(location, v0, v1) :: :ok when location: integer(), v0: integer(), v1: integer()
  def uniform2i(location, v0, v1), do: cast(5481, <<location :: 32-native-signed, v0 :: 32-native-signed, v1 :: 32-native-signed>>)

  @spec uniform2iv(location, value) :: :ok when location: integer(), value: [{integer(), integer()}]
  def uniform2iv(location, value) do
    valueLen = length(value)
    cast(5489, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2} <- value do
      <<v1 :: 32-native-signed, v2 :: 32-native-signed>>
    end) :: binary>>)
  end

  @spec uniform2ui(location, v0, v1) :: :ok when location: integer(), v0: integer(), v1: integer()
  def uniform2ui(location, v0, v1), do: cast(5562, <<location :: 32-native-signed, v0 :: 32-native-unsigned, v1 :: 32-native-unsigned>>)

  @spec uniform2uiv(location, value) :: :ok when location: integer(), value: [{integer(), integer()}]
  def uniform2uiv(location, value) do
    valueLen = length(value)
    cast(5566, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2} <- value do
      <<v1 :: 32-native-unsigned, v2 :: 32-native-unsigned>>
    end) :: binary>>)
  end

  @spec uniform3d(location, x, y, z) :: :ok when location: integer(), x: float(), y: float(), z: float()
  def uniform3d(location, x, y, z), do: cast(5733, <<location :: 32-native-signed, 0 :: 32, x :: 64-native-float, y :: 64-native-float, z :: 64-native-float>>)

  @spec uniform3dv(location, value) :: :ok when location: integer(), value: [{float(), float(), float()}]
  def uniform3dv(location, value) do
    valueLen = length(value)
    cast(5737, <<location :: 32-native-signed, 0 :: 32, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniform3f(location, v0, v1, v2) :: :ok when location: integer(), v0: float(), v1: float(), v2: float()
  def uniform3f(location, v0, v1, v2), do: cast(5478, <<location :: 32-native-signed, v0 :: 32-native-float, v1 :: 32-native-float, v2 :: 32-native-float>>)

  @spec uniform3fv(location, value) :: :ok when location: integer(), value: [{float(), float(), float()}]
  def uniform3fv(location, value) do
    valueLen = length(value)
    cast(5486, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniform3i(location, v0, v1, v2) :: :ok when location: integer(), v0: integer(), v1: integer(), v2: integer()
  def uniform3i(location, v0, v1, v2), do: cast(5482, <<location :: 32-native-signed, v0 :: 32-native-signed, v1 :: 32-native-signed, v2 :: 32-native-signed>>)

  @spec uniform3iv(location, value) :: :ok when location: integer(), value: [{integer(), integer(), integer()}]
  def uniform3iv(location, value) do
    valueLen = length(value)
    cast(5490, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3} <- value do
      <<v1 :: 32-native-signed, v2 :: 32-native-signed, v3 :: 32-native-signed>>
    end) :: binary>>)
  end

  @spec uniform3ui(location, v0, v1, v2) :: :ok when location: integer(), v0: integer(), v1: integer(), v2: integer()
  def uniform3ui(location, v0, v1, v2), do: cast(5563, <<location :: 32-native-signed, v0 :: 32-native-unsigned, v1 :: 32-native-unsigned, v2 :: 32-native-unsigned>>)

  @spec uniform3uiv(location, value) :: :ok when location: integer(), value: [{integer(), integer(), integer()}]
  def uniform3uiv(location, value) do
    valueLen = length(value)
    cast(5567, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3} <- value do
      <<v1 :: 32-native-unsigned, v2 :: 32-native-unsigned, v3 :: 32-native-unsigned>>
    end) :: binary>>)
  end

  @spec uniform4d(location, x, y, z, w) :: :ok when location: integer(), x: float(), y: float(), z: float(), w: float()
  def uniform4d(location, x, y, z, w), do: cast(5734, <<location :: 32-native-signed, 0 :: 32, x :: 64-native-float, y :: 64-native-float, z :: 64-native-float, w :: 64-native-float>>)

  @spec uniform4dv(location, value) :: :ok when location: integer(), value: [{float(), float(), float(), float()}]
  def uniform4dv(location, value) do
    valueLen = length(value)
    cast(5738, <<location :: 32-native-signed, 0 :: 32, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniform4f(location, v0, v1, v2, v3) :: :ok when location: integer(), v0: float(), v1: float(), v2: float(), v3: float()
  def uniform4f(location, v0, v1, v2, v3), do: cast(5479, <<location :: 32-native-signed, v0 :: 32-native-float, v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float>>)

  @spec uniform4fv(location, value) :: :ok when location: integer(), value: [{float(), float(), float(), float()}]
  def uniform4fv(location, value) do
    valueLen = length(value)
    cast(5487, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniform4i(location, v0, v1, v2, v3) :: :ok when location: integer(), v0: integer(), v1: integer(), v2: integer(), v3: integer()
  def uniform4i(location, v0, v1, v2, v3), do: cast(5483, <<location :: 32-native-signed, v0 :: 32-native-signed, v1 :: 32-native-signed, v2 :: 32-native-signed, v3 :: 32-native-signed>>)

  @spec uniform4iv(location, value) :: :ok when location: integer(), value: [{integer(), integer(), integer(), integer()}]
  def uniform4iv(location, value) do
    valueLen = length(value)
    cast(5491, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 32-native-signed, v2 :: 32-native-signed, v3 :: 32-native-signed, v4 :: 32-native-signed>>
    end) :: binary>>)
  end

  @spec uniform4ui(location, v0, v1, v2, v3) :: :ok when location: integer(), v0: integer(), v1: integer(), v2: integer(), v3: integer()
  def uniform4ui(location, v0, v1, v2, v3), do: cast(5564, <<location :: 32-native-signed, v0 :: 32-native-unsigned, v1 :: 32-native-unsigned, v2 :: 32-native-unsigned, v3 :: 32-native-unsigned>>)

  @spec uniform4uiv(location, value) :: :ok when location: integer(), value: [{integer(), integer(), integer(), integer()}]
  def uniform4uiv(location, value) do
    valueLen = length(value)
    cast(5568, <<location :: 32-native-signed, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 32-native-unsigned, v2 :: 32-native-unsigned, v3 :: 32-native-unsigned, v4 :: 32-native-unsigned>>
    end) :: binary>>)
  end

  @spec uniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding) :: :ok when program: integer(), uniformBlockIndex: integer(), uniformBlockBinding: integer()
  def uniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding), do: cast(5682, <<program :: 32-native-unsigned, uniformBlockIndex :: 32-native-unsigned, uniformBlockBinding :: 32-native-unsigned>>)

  @spec uniformMatrix2dv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float()}]
  def uniformMatrix2dv(location, transpose, value) do
    valueLen = length(value)
    cast(5739, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix2fv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float()}]
  def uniformMatrix2fv(location, transpose, value) do
    valueLen = length(value)
    cast(5492, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix2x3dv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float()}]
  def uniformMatrix2x3dv(location, transpose, value) do
    valueLen = length(value)
    cast(5742, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix2x3fv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float()}]
  def uniformMatrix2x3fv(location, transpose, value) do
    valueLen = length(value)
    cast(5521, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix2x4dv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix2x4dv(location, transpose, value) do
    valueLen = length(value)
    cast(5743, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix2x4fv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix2x4fv(location, transpose, value) do
    valueLen = length(value)
    cast(5523, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix3dv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix3dv(location, transpose, value) do
    valueLen = length(value)
    cast(5740, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float, v9 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix3fv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix3fv(location, transpose, value) do
    valueLen = length(value)
    cast(5493, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float, v9 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix3x2dv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float()}]
  def uniformMatrix3x2dv(location, transpose, value) do
    valueLen = length(value)
    cast(5744, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix3x2fv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float()}]
  def uniformMatrix3x2fv(location, transpose, value) do
    valueLen = length(value)
    cast(5522, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix3x4dv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix3x4dv(location, transpose, value) do
    valueLen = length(value)
    cast(5745, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float, v9 :: 64-native-float, v10 :: 64-native-float, v11 :: 64-native-float, v12 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix3x4fv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix3x4fv(location, transpose, value) do
    valueLen = length(value)
    cast(5525, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float, v9 :: 32-native-float, v10 :: 32-native-float, v11 :: 32-native-float, v12 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix4dv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix4dv(location, transpose, value) do
    valueLen = length(value)
    cast(5741, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float, v9 :: 64-native-float, v10 :: 64-native-float, v11 :: 64-native-float, v12 :: 64-native-float, v13 :: 64-native-float, v14 :: 64-native-float, v15 :: 64-native-float, v16 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix4fv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix4fv(location, transpose, value) do
    valueLen = length(value)
    cast(5494, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float, v9 :: 32-native-float, v10 :: 32-native-float, v11 :: 32-native-float, v12 :: 32-native-float, v13 :: 32-native-float, v14 :: 32-native-float, v15 :: 32-native-float, v16 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix4x2dv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix4x2dv(location, transpose, value) do
    valueLen = length(value)
    cast(5746, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix4x2fv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix4x2fv(location, transpose, value) do
    valueLen = length(value)
    cast(5524, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix4x3dv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix4x3dv(location, transpose, value) do
    valueLen = length(value)
    cast(5747, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, 0 :: 32, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12} <- value do
      <<v1 :: 64-native-float, v2 :: 64-native-float, v3 :: 64-native-float, v4 :: 64-native-float, v5 :: 64-native-float, v6 :: 64-native-float, v7 :: 64-native-float, v8 :: 64-native-float, v9 :: 64-native-float, v10 :: 64-native-float, v11 :: 64-native-float, v12 :: 64-native-float>>
    end) :: binary>>)
  end

  @spec uniformMatrix4x3fv(location, transpose, value) :: :ok when location: integer(), transpose: (0 | 1), value: [{float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float(), float()}]
  def uniformMatrix4x3fv(location, transpose, value) do
    valueLen = length(value)
    cast(5526, <<location :: 32-native-signed, transpose :: 8-native-unsigned, 0 :: 24, valueLen :: 32-native-unsigned, (for {v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12} <- value do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float, v5 :: 32-native-float, v6 :: 32-native-float, v7 :: 32-native-float, v8 :: 32-native-float, v9 :: 32-native-float, v10 :: 32-native-float, v11 :: 32-native-float, v12 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec uniformSubroutinesuiv(shadertype, indices) :: :ok when shadertype: enum(), indices: [integer()]
  def uniformSubroutinesuiv(shadertype, indices) do
    indicesLen = length(indices)
    cast(5753, <<shadertype :: 32-native-unsigned, indicesLen :: 32-native-unsigned, (for c <- indices do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(indicesLen, 2) * 32)>>)
  end

  @spec useProgram(program) :: :ok when program: integer()
  def useProgram(program), do: cast(5475, <<program :: 32-native-unsigned>>)

  @spec useProgramObjectARB(programObj) :: :ok when programObj: integer()
  def useProgramObjectARB(programObj), do: cast(5636, <<programObj :: 64-native-unsigned>>)

  @spec useProgramStages(pipeline, stages, program) :: :ok when pipeline: integer(), stages: integer(), program: integer()
  def useProgramStages(pipeline, stages, program), do: cast(5777, <<pipeline :: 32-native-unsigned, stages :: 32-native-unsigned, program :: 32-native-unsigned>>)

  @spec validateProgram(program) :: :ok when program: integer()
  def validateProgram(program), do: cast(5495, <<program :: 32-native-unsigned>>)

  @spec validateProgramARB(programObj) :: :ok when programObj: integer()
  def validateProgramARB(programObj), do: cast(5637, <<programObj :: 64-native-unsigned>>)

  @spec validateProgramPipeline(pipeline) :: :ok when pipeline: integer()
  def validateProgramPipeline(pipeline), do: cast(5835, <<pipeline :: 32-native-unsigned>>)

  @spec vertex2d(x, y) :: :ok when x: float(), y: float()
  def vertex2d(x, y), do: cast(5112, <<x :: 64-native-float, y :: 64-native-float>>)

  @spec vertex2dv(v) :: :ok when v: {x :: float(), y :: float()}
  def vertex2dv({x, y}), do: vertex2d(x, y)

  @spec vertex2f(x, y) :: :ok when x: float(), y: float()
  def vertex2f(x, y), do: cast(5113, <<x :: 32-native-float, y :: 32-native-float>>)

  @spec vertex2fv(v) :: :ok when v: {x :: float(), y :: float()}
  def vertex2fv({x, y}), do: vertex2f(x, y)

  @spec vertex2i(x, y) :: :ok when x: integer(), y: integer()
  def vertex2i(x, y), do: cast(5114, <<x :: 32-native-signed, y :: 32-native-signed>>)

  @spec vertex2iv(v) :: :ok when v: {x :: integer(), y :: integer()}
  def vertex2iv({x, y}), do: vertex2i(x, y)

  @spec vertex2s(x, y) :: :ok when x: integer(), y: integer()
  def vertex2s(x, y), do: cast(5115, <<x :: 16-native-signed, y :: 16-native-signed>>)

  @spec vertex2sv(v) :: :ok when v: {x :: integer(), y :: integer()}
  def vertex2sv({x, y}), do: vertex2s(x, y)

  @spec vertex3d(x, y, z) :: :ok when x: float(), y: float(), z: float()
  def vertex3d(x, y, z), do: cast(5116, <<x :: 64-native-float, y :: 64-native-float, z :: 64-native-float>>)

  @spec vertex3dv(v) :: :ok when v: {x :: float(), y :: float(), z :: float()}
  def vertex3dv({x, y, z}), do: vertex3d(x, y, z)

  @spec vertex3f(x, y, z) :: :ok when x: float(), y: float(), z: float()
  def vertex3f(x, y, z), do: cast(5117, <<x :: 32-native-float, y :: 32-native-float, z :: 32-native-float>>)

  @spec vertex3fv(v) :: :ok when v: {x :: float(), y :: float(), z :: float()}
  def vertex3fv({x, y, z}), do: vertex3f(x, y, z)

  @spec vertex3i(x, y, z) :: :ok when x: integer(), y: integer(), z: integer()
  def vertex3i(x, y, z), do: cast(5118, <<x :: 32-native-signed, y :: 32-native-signed, z :: 32-native-signed>>)

  @spec vertex3iv(v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer()}
  def vertex3iv({x, y, z}), do: vertex3i(x, y, z)

  @spec vertex3s(x, y, z) :: :ok when x: integer(), y: integer(), z: integer()
  def vertex3s(x, y, z), do: cast(5119, <<x :: 16-native-signed, y :: 16-native-signed, z :: 16-native-signed>>)

  @spec vertex3sv(v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer()}
  def vertex3sv({x, y, z}), do: vertex3s(x, y, z)

  @spec vertex4d(x, y, z, w) :: :ok when x: float(), y: float(), z: float(), w: float()
  def vertex4d(x, y, z, w), do: cast(5120, <<x :: 64-native-float, y :: 64-native-float, z :: 64-native-float, w :: 64-native-float>>)

  @spec vertex4dv(v) :: :ok when v: {x :: float(), y :: float(), z :: float(), w :: float()}
  def vertex4dv({x, y, z, w}), do: vertex4d(x, y, z, w)

  @spec vertex4f(x, y, z, w) :: :ok when x: float(), y: float(), z: float(), w: float()
  def vertex4f(x, y, z, w), do: cast(5121, <<x :: 32-native-float, y :: 32-native-float, z :: 32-native-float, w :: 32-native-float>>)

  @spec vertex4fv(v) :: :ok when v: {x :: float(), y :: float(), z :: float(), w :: float()}
  def vertex4fv({x, y, z, w}), do: vertex4f(x, y, z, w)

  @spec vertex4i(x, y, z, w) :: :ok when x: integer(), y: integer(), z: integer(), w: integer()
  def vertex4i(x, y, z, w), do: cast(5122, <<x :: 32-native-signed, y :: 32-native-signed, z :: 32-native-signed, w :: 32-native-signed>>)

  @spec vertex4iv(v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer(), w :: integer()}
  def vertex4iv({x, y, z, w}), do: vertex4i(x, y, z, w)

  @spec vertex4s(x, y, z, w) :: :ok when x: integer(), y: integer(), z: integer(), w: integer()
  def vertex4s(x, y, z, w), do: cast(5123, <<x :: 16-native-signed, y :: 16-native-signed, z :: 16-native-signed, w :: 16-native-signed>>)

  @spec vertex4sv(v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer(), w :: integer()}
  def vertex4sv({x, y, z, w}), do: vertex4s(x, y, z, w)

  @spec vertexAttrib1d(index, x) :: :ok when index: integer(), x: float()
  def vertexAttrib1d(index, x), do: cast(5496, <<index :: 32-native-unsigned, 0 :: 32, x :: 64-native-float>>)

  @spec vertexAttrib1dv(index :: integer(), v) :: :ok when v: {x :: float()}
  def vertexAttrib1dv(index, {x}), do: vertexAttrib1d(index, x)

  @spec vertexAttrib1f(index, x) :: :ok when index: integer(), x: float()
  def vertexAttrib1f(index, x), do: cast(5497, <<index :: 32-native-unsigned, x :: 32-native-float>>)

  @spec vertexAttrib1fv(index :: integer(), v) :: :ok when v: {x :: float()}
  def vertexAttrib1fv(index, {x}), do: vertexAttrib1f(index, x)

  @spec vertexAttrib1s(index, x) :: :ok when index: integer(), x: integer()
  def vertexAttrib1s(index, x), do: cast(5498, <<index :: 32-native-unsigned, x :: 16-native-signed>>)

  @spec vertexAttrib1sv(index :: integer(), v) :: :ok when v: {x :: integer()}
  def vertexAttrib1sv(index, {x}), do: vertexAttrib1s(index, x)

  @spec vertexAttrib2d(index, x, y) :: :ok when index: integer(), x: float(), y: float()
  def vertexAttrib2d(index, x, y), do: cast(5499, <<index :: 32-native-unsigned, 0 :: 32, x :: 64-native-float, y :: 64-native-float>>)

  @spec vertexAttrib2dv(index :: integer(), v) :: :ok when v: {x :: float(), y :: float()}
  def vertexAttrib2dv(index, {x, y}), do: vertexAttrib2d(index, x, y)

  @spec vertexAttrib2f(index, x, y) :: :ok when index: integer(), x: float(), y: float()
  def vertexAttrib2f(index, x, y), do: cast(5500, <<index :: 32-native-unsigned, x :: 32-native-float, y :: 32-native-float>>)

  @spec vertexAttrib2fv(index :: integer(), v) :: :ok when v: {x :: float(), y :: float()}
  def vertexAttrib2fv(index, {x, y}), do: vertexAttrib2f(index, x, y)

  @spec vertexAttrib2s(index, x, y) :: :ok when index: integer(), x: integer(), y: integer()
  def vertexAttrib2s(index, x, y), do: cast(5501, <<index :: 32-native-unsigned, x :: 16-native-signed, y :: 16-native-signed>>)

  @spec vertexAttrib2sv(index :: integer(), v) :: :ok when v: {x :: integer(), y :: integer()}
  def vertexAttrib2sv(index, {x, y}), do: vertexAttrib2s(index, x, y)

  @spec vertexAttrib3d(index, x, y, z) :: :ok when index: integer(), x: float(), y: float(), z: float()
  def vertexAttrib3d(index, x, y, z), do: cast(5502, <<index :: 32-native-unsigned, 0 :: 32, x :: 64-native-float, y :: 64-native-float, z :: 64-native-float>>)

  @spec vertexAttrib3dv(index :: integer(), v) :: :ok when v: {x :: float(), y :: float(), z :: float()}
  def vertexAttrib3dv(index, {x, y, z}), do: vertexAttrib3d(index, x, y, z)

  @spec vertexAttrib3f(index, x, y, z) :: :ok when index: integer(), x: float(), y: float(), z: float()
  def vertexAttrib3f(index, x, y, z), do: cast(5503, <<index :: 32-native-unsigned, x :: 32-native-float, y :: 32-native-float, z :: 32-native-float>>)

  @spec vertexAttrib3fv(index :: integer(), v) :: :ok when v: {x :: float(), y :: float(), z :: float()}
  def vertexAttrib3fv(index, {x, y, z}), do: vertexAttrib3f(index, x, y, z)

  @spec vertexAttrib3s(index, x, y, z) :: :ok when index: integer(), x: integer(), y: integer(), z: integer()
  def vertexAttrib3s(index, x, y, z), do: cast(5504, <<index :: 32-native-unsigned, x :: 16-native-signed, y :: 16-native-signed, z :: 16-native-signed>>)

  @spec vertexAttrib3sv(index :: integer(), v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer()}
  def vertexAttrib3sv(index, {x, y, z}), do: vertexAttrib3s(index, x, y, z)

  @spec vertexAttrib4Nbv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttrib4Nbv(index, {v1, v2, v3, v4}), do: cast(5505, <<index :: 32-native-unsigned, v1 :: 8-native-signed, v2 :: 8-native-signed, v3 :: 8-native-signed, v4 :: 8-native-signed>>)

  @spec vertexAttrib4Niv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttrib4Niv(index, {v1, v2, v3, v4}), do: cast(5506, <<index :: 32-native-unsigned, v1 :: 32-native-signed, v2 :: 32-native-signed, v3 :: 32-native-signed, v4 :: 32-native-signed>>)

  @spec vertexAttrib4Nsv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttrib4Nsv(index, {v1, v2, v3, v4}), do: cast(5507, <<index :: 32-native-unsigned, v1 :: 16-native-signed, v2 :: 16-native-signed, v3 :: 16-native-signed, v4 :: 16-native-signed>>)

  @spec vertexAttrib4Nub(index, x, y, z, w) :: :ok when index: integer(), x: integer(), y: integer(), z: integer(), w: integer()
  def vertexAttrib4Nub(index, x, y, z, w), do: cast(5508, <<index :: 32-native-unsigned, x :: 8-native-unsigned, y :: 8-native-unsigned, z :: 8-native-unsigned, w :: 8-native-unsigned>>)

  @spec vertexAttrib4Nubv(index :: integer(), v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer(), w :: integer()}
  def vertexAttrib4Nubv(index, {x, y, z, w}), do: vertexAttrib4Nub(index, x, y, z, w)

  @spec vertexAttrib4Nuiv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttrib4Nuiv(index, {v1, v2, v3, v4}), do: cast(5509, <<index :: 32-native-unsigned, v1 :: 32-native-unsigned, v2 :: 32-native-unsigned, v3 :: 32-native-unsigned, v4 :: 32-native-unsigned>>)

  @spec vertexAttrib4Nusv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttrib4Nusv(index, {v1, v2, v3, v4}), do: cast(5510, <<index :: 32-native-unsigned, v1 :: 16-native-unsigned, v2 :: 16-native-unsigned, v3 :: 16-native-unsigned, v4 :: 16-native-unsigned>>)

  @spec vertexAttrib4bv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttrib4bv(index, {v1, v2, v3, v4}), do: cast(5511, <<index :: 32-native-unsigned, v1 :: 8-native-signed, v2 :: 8-native-signed, v3 :: 8-native-signed, v4 :: 8-native-signed>>)

  @spec vertexAttrib4d(index, x, y, z, w) :: :ok when index: integer(), x: float(), y: float(), z: float(), w: float()
  def vertexAttrib4d(index, x, y, z, w), do: cast(5512, <<index :: 32-native-unsigned, 0 :: 32, x :: 64-native-float, y :: 64-native-float, z :: 64-native-float, w :: 64-native-float>>)

  @spec vertexAttrib4dv(index :: integer(), v) :: :ok when v: {x :: float(), y :: float(), z :: float(), w :: float()}
  def vertexAttrib4dv(index, {x, y, z, w}), do: vertexAttrib4d(index, x, y, z, w)

  @spec vertexAttrib4f(index, x, y, z, w) :: :ok when index: integer(), x: float(), y: float(), z: float(), w: float()
  def vertexAttrib4f(index, x, y, z, w), do: cast(5513, <<index :: 32-native-unsigned, x :: 32-native-float, y :: 32-native-float, z :: 32-native-float, w :: 32-native-float>>)

  @spec vertexAttrib4fv(index :: integer(), v) :: :ok when v: {x :: float(), y :: float(), z :: float(), w :: float()}
  def vertexAttrib4fv(index, {x, y, z, w}), do: vertexAttrib4f(index, x, y, z, w)

  @spec vertexAttrib4iv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttrib4iv(index, {v1, v2, v3, v4}), do: cast(5514, <<index :: 32-native-unsigned, v1 :: 32-native-signed, v2 :: 32-native-signed, v3 :: 32-native-signed, v4 :: 32-native-signed>>)

  @spec vertexAttrib4s(index, x, y, z, w) :: :ok when index: integer(), x: integer(), y: integer(), z: integer(), w: integer()
  def vertexAttrib4s(index, x, y, z, w), do: cast(5515, <<index :: 32-native-unsigned, x :: 16-native-signed, y :: 16-native-signed, z :: 16-native-signed, w :: 16-native-signed>>)

  @spec vertexAttrib4sv(index :: integer(), v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer(), w :: integer()}
  def vertexAttrib4sv(index, {x, y, z, w}), do: vertexAttrib4s(index, x, y, z, w)

  @spec vertexAttrib4ubv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttrib4ubv(index, {v1, v2, v3, v4}), do: cast(5516, <<index :: 32-native-unsigned, v1 :: 8-native-unsigned, v2 :: 8-native-unsigned, v3 :: 8-native-unsigned, v4 :: 8-native-unsigned>>)

  @spec vertexAttrib4uiv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttrib4uiv(index, {v1, v2, v3, v4}), do: cast(5517, <<index :: 32-native-unsigned, v1 :: 32-native-unsigned, v2 :: 32-native-unsigned, v3 :: 32-native-unsigned, v4 :: 32-native-unsigned>>)

  @spec vertexAttrib4usv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttrib4usv(index, {v1, v2, v3, v4}), do: cast(5518, <<index :: 32-native-unsigned, v1 :: 16-native-unsigned, v2 :: 16-native-unsigned, v3 :: 16-native-unsigned, v4 :: 16-native-unsigned>>)

  @spec vertexAttribDivisor(index, divisor) :: :ok when index: integer(), divisor: integer()
  def vertexAttribDivisor(index, divisor), do: cast(5586, <<index :: 32-native-unsigned, divisor :: 32-native-unsigned>>)

  @spec vertexAttribI1i(index, x) :: :ok when index: integer(), x: integer()
  def vertexAttribI1i(index, x), do: cast(5546, <<index :: 32-native-unsigned, x :: 32-native-signed>>)

  @spec vertexAttribI1iv(index :: integer(), v) :: :ok when v: {x :: integer()}
  def vertexAttribI1iv(index, {x}), do: vertexAttribI1i(index, x)

  @spec vertexAttribI1ui(index, x) :: :ok when index: integer(), x: integer()
  def vertexAttribI1ui(index, x), do: cast(5550, <<index :: 32-native-unsigned, x :: 32-native-unsigned>>)

  @spec vertexAttribI1uiv(index :: integer(), v) :: :ok when v: {x :: integer()}
  def vertexAttribI1uiv(index, {x}), do: vertexAttribI1ui(index, x)

  @spec vertexAttribI2i(index, x, y) :: :ok when index: integer(), x: integer(), y: integer()
  def vertexAttribI2i(index, x, y), do: cast(5547, <<index :: 32-native-unsigned, x :: 32-native-signed, y :: 32-native-signed>>)

  @spec vertexAttribI2iv(index :: integer(), v) :: :ok when v: {x :: integer(), y :: integer()}
  def vertexAttribI2iv(index, {x, y}), do: vertexAttribI2i(index, x, y)

  @spec vertexAttribI2ui(index, x, y) :: :ok when index: integer(), x: integer(), y: integer()
  def vertexAttribI2ui(index, x, y), do: cast(5551, <<index :: 32-native-unsigned, x :: 32-native-unsigned, y :: 32-native-unsigned>>)

  @spec vertexAttribI2uiv(index :: integer(), v) :: :ok when v: {x :: integer(), y :: integer()}
  def vertexAttribI2uiv(index, {x, y}), do: vertexAttribI2ui(index, x, y)

  @spec vertexAttribI3i(index, x, y, z) :: :ok when index: integer(), x: integer(), y: integer(), z: integer()
  def vertexAttribI3i(index, x, y, z), do: cast(5548, <<index :: 32-native-unsigned, x :: 32-native-signed, y :: 32-native-signed, z :: 32-native-signed>>)

  @spec vertexAttribI3iv(index :: integer(), v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer()}
  def vertexAttribI3iv(index, {x, y, z}), do: vertexAttribI3i(index, x, y, z)

  @spec vertexAttribI3ui(index, x, y, z) :: :ok when index: integer(), x: integer(), y: integer(), z: integer()
  def vertexAttribI3ui(index, x, y, z), do: cast(5552, <<index :: 32-native-unsigned, x :: 32-native-unsigned, y :: 32-native-unsigned, z :: 32-native-unsigned>>)

  @spec vertexAttribI3uiv(index :: integer(), v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer()}
  def vertexAttribI3uiv(index, {x, y, z}), do: vertexAttribI3ui(index, x, y, z)

  @spec vertexAttribI4bv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttribI4bv(index, {v1, v2, v3, v4}), do: cast(5554, <<index :: 32-native-unsigned, v1 :: 8-native-signed, v2 :: 8-native-signed, v3 :: 8-native-signed, v4 :: 8-native-signed>>)

  @spec vertexAttribI4i(index, x, y, z, w) :: :ok when index: integer(), x: integer(), y: integer(), z: integer(), w: integer()
  def vertexAttribI4i(index, x, y, z, w), do: cast(5549, <<index :: 32-native-unsigned, x :: 32-native-signed, y :: 32-native-signed, z :: 32-native-signed, w :: 32-native-signed>>)

  @spec vertexAttribI4iv(index :: integer(), v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer(), w :: integer()}
  def vertexAttribI4iv(index, {x, y, z, w}), do: vertexAttribI4i(index, x, y, z, w)

  @spec vertexAttribI4sv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttribI4sv(index, {v1, v2, v3, v4}), do: cast(5555, <<index :: 32-native-unsigned, v1 :: 16-native-signed, v2 :: 16-native-signed, v3 :: 16-native-signed, v4 :: 16-native-signed>>)

  @spec vertexAttribI4ubv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttribI4ubv(index, {v1, v2, v3, v4}), do: cast(5556, <<index :: 32-native-unsigned, v1 :: 8-native-unsigned, v2 :: 8-native-unsigned, v3 :: 8-native-unsigned, v4 :: 8-native-unsigned>>)

  @spec vertexAttribI4ui(index, x, y, z, w) :: :ok when index: integer(), x: integer(), y: integer(), z: integer(), w: integer()
  def vertexAttribI4ui(index, x, y, z, w), do: cast(5553, <<index :: 32-native-unsigned, x :: 32-native-unsigned, y :: 32-native-unsigned, z :: 32-native-unsigned, w :: 32-native-unsigned>>)

  @spec vertexAttribI4uiv(index :: integer(), v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer(), w :: integer()}
  def vertexAttribI4uiv(index, {x, y, z, w}), do: vertexAttribI4ui(index, x, y, z, w)

  @spec vertexAttribI4usv(index, v) :: :ok when index: integer(), v: {integer(), integer(), integer(), integer()}
  def vertexAttribI4usv(index, {v1, v2, v3, v4}), do: cast(5557, <<index :: 32-native-unsigned, v1 :: 16-native-unsigned, v2 :: 16-native-unsigned, v3 :: 16-native-unsigned, v4 :: 16-native-unsigned>>)

  @spec vertexAttribIPointer(index, size, type, stride, pointer) :: :ok when index: integer(), size: integer(), type: enum(), stride: integer(), pointer: (offset() | mem())
  def vertexAttribIPointer(index, size, type, stride, pointer) when is_integer(pointer), do: cast(5542, <<index :: 32-native-unsigned, size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed, pointer :: 32-native-unsigned>>)

  def vertexAttribIPointer(index, size, type, stride, pointer) do
    send_bin(pointer)
    cast(5543, <<index :: 32-native-unsigned, size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed>>)
  end

  @spec vertexAttribL1d(index, x) :: :ok when index: integer(), x: float()
  def vertexAttribL1d(index, x), do: cast(5837, <<index :: 32-native-unsigned, 0 :: 32, x :: 64-native-float>>)

  @spec vertexAttribL1dv(index :: integer(), v) :: :ok when v: {x :: float()}
  def vertexAttribL1dv(index, {x}), do: vertexAttribL1d(index, x)

  @spec vertexAttribL2d(index, x, y) :: :ok when index: integer(), x: float(), y: float()
  def vertexAttribL2d(index, x, y), do: cast(5838, <<index :: 32-native-unsigned, 0 :: 32, x :: 64-native-float, y :: 64-native-float>>)

  @spec vertexAttribL2dv(index :: integer(), v) :: :ok when v: {x :: float(), y :: float()}
  def vertexAttribL2dv(index, {x, y}), do: vertexAttribL2d(index, x, y)

  @spec vertexAttribL3d(index, x, y, z) :: :ok when index: integer(), x: float(), y: float(), z: float()
  def vertexAttribL3d(index, x, y, z), do: cast(5839, <<index :: 32-native-unsigned, 0 :: 32, x :: 64-native-float, y :: 64-native-float, z :: 64-native-float>>)

  @spec vertexAttribL3dv(index :: integer(), v) :: :ok when v: {x :: float(), y :: float(), z :: float()}
  def vertexAttribL3dv(index, {x, y, z}), do: vertexAttribL3d(index, x, y, z)

  @spec vertexAttribL4d(index, x, y, z, w) :: :ok when index: integer(), x: float(), y: float(), z: float(), w: float()
  def vertexAttribL4d(index, x, y, z, w), do: cast(5840, <<index :: 32-native-unsigned, 0 :: 32, x :: 64-native-float, y :: 64-native-float, z :: 64-native-float, w :: 64-native-float>>)

  @spec vertexAttribL4dv(index :: integer(), v) :: :ok when v: {x :: float(), y :: float(), z :: float(), w :: float()}
  def vertexAttribL4dv(index, {x, y, z, w}), do: vertexAttribL4d(index, x, y, z, w)

  @spec vertexAttribLPointer(index, size, type, stride, pointer) :: :ok when index: integer(), size: integer(), type: enum(), stride: integer(), pointer: (offset() | mem())
  def vertexAttribLPointer(index, size, type, stride, pointer) when is_integer(pointer), do: cast(5841, <<index :: 32-native-unsigned, size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed, pointer :: 32-native-unsigned>>)

  def vertexAttribLPointer(index, size, type, stride, pointer) do
    send_bin(pointer)
    cast(5842, <<index :: 32-native-unsigned, size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed>>)
  end

  @spec vertexAttribPointer(index, size, type, normalized, stride, pointer) :: :ok when index: integer(), size: integer(), type: enum(), normalized: (0 | 1), stride: integer(), pointer: (offset() | mem())
  def vertexAttribPointer(index, size, type, normalized, stride, pointer) when is_integer(pointer), do: cast(5519, <<index :: 32-native-unsigned, size :: 32-native-signed, type :: 32-native-unsigned, normalized :: 8-native-unsigned, 0 :: 24, stride :: 32-native-signed, pointer :: 32-native-unsigned>>)

  def vertexAttribPointer(index, size, type, normalized, stride, pointer) do
    send_bin(pointer)
    cast(5520, <<index :: 32-native-unsigned, size :: 32-native-signed, type :: 32-native-unsigned, normalized :: 8-native-unsigned, 0 :: 24, stride :: 32-native-signed>>)
  end

  @spec vertexBlendARB(count) :: :ok when count: integer()
  def vertexBlendARB(count), do: cast(5604, <<count :: 32-native-signed>>)

  @spec vertexPointer(size, type, stride, ptr) :: :ok when size: integer(), type: enum(), stride: integer(), ptr: (offset() | mem())
  def vertexPointer(size, type, stride, ptr) when is_integer(ptr), do: cast(5186, <<size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed, ptr :: 32-native-unsigned>>)

  def vertexPointer(size, type, stride, ptr) do
    send_bin(ptr)
    cast(5187, <<size :: 32-native-signed, type :: 32-native-unsigned, stride :: 32-native-signed>>)
  end

  @spec viewport(x, y, width, height) :: :ok when x: integer(), y: integer(), width: integer(), height: integer()
  def viewport(x, y, width, height), do: cast(5088, <<x :: 32-native-signed, y :: 32-native-signed, width :: 32-native-signed, height :: 32-native-signed>>)

  @spec viewportArrayv(first, v) :: :ok when first: integer(), v: [{float(), float(), float(), float()}]
  def viewportArrayv(first, v) do
    vLen = length(v)
    cast(5844, <<first :: 32-native-unsigned, vLen :: 32-native-unsigned, (for {v1, v2, v3, v4} <- v do
      <<v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float>>
    end) :: binary>>)
  end

  @spec viewportIndexedf(index, x, y, w, h) :: :ok when index: integer(), x: float(), y: float(), w: float(), h: float()
  def viewportIndexedf(index, x, y, w, h), do: cast(5845, <<index :: 32-native-unsigned, x :: 32-native-float, y :: 32-native-float, w :: 32-native-float, h :: 32-native-float>>)

  @spec viewportIndexedfv(index, v) :: :ok when index: integer(), v: {float(), float(), float(), float()}
  def viewportIndexedfv(index, {v1, v2, v3, v4}), do: cast(5846, <<index :: 32-native-unsigned, v1 :: 32-native-float, v2 :: 32-native-float, v3 :: 32-native-float, v4 :: 32-native-float>>)

  @spec waitSync(sync, flags, timeout) :: :ok when sync: integer(), flags: integer(), timeout: integer()
  def waitSync(sync, flags, timeout), do: cast(5695, <<sync :: 64-native-unsigned, flags :: 32-native-unsigned, 0 :: 32, timeout :: 64-native-unsigned>>)

  @spec weightbvARB(weights) :: :ok when weights: [integer()]
  def weightbvARB(weights) do
    weightsLen = length(weights)
    cast(5596, <<weightsLen :: 32-native-unsigned, (for c <- weights do
      <<c :: 8-native-signed>>
    end) :: binary, 0 :: size(rem(8 - rem(weightsLen + 4, 8), 8))>>)
  end

  @spec weightdvARB(weights) :: :ok when weights: [float()]
  def weightdvARB(weights) do
    weightsLen = length(weights)
    cast(5600, <<weightsLen :: 32-native-unsigned, 0 :: 32, (for c <- weights do
      <<c :: 64-native-float>>
    end) :: binary>>)
  end

  @spec weightfvARB(weights) :: :ok when weights: [float()]
  def weightfvARB(weights) do
    weightsLen = length(weights)
    cast(5599, <<weightsLen :: 32-native-unsigned, (for c <- weights do
      <<c :: 32-native-float>>
    end) :: binary, 0 :: size(rem(1 + weightsLen, 2) * 32)>>)
  end

  @spec weightivARB(weights) :: :ok when weights: [integer()]
  def weightivARB(weights) do
    weightsLen = length(weights)
    cast(5598, <<weightsLen :: 32-native-unsigned, (for c <- weights do
      <<c :: 32-native-signed>>
    end) :: binary, 0 :: size(rem(1 + weightsLen, 2) * 32)>>)
  end

  @spec weightsvARB(weights) :: :ok when weights: [integer()]
  def weightsvARB(weights) do
    weightsLen = length(weights)
    cast(5597, <<weightsLen :: 32-native-unsigned, (for c <- weights do
      <<c :: 16-native-signed>>
    end) :: binary, 0 :: size(rem(8 - rem(weightsLen * 2 + 4, 8), 8))>>)
  end

  @spec weightubvARB(weights) :: :ok when weights: [integer()]
  def weightubvARB(weights) do
    weightsLen = length(weights)
    cast(5601, <<weightsLen :: 32-native-unsigned, (for c <- weights do
      <<c :: 8-native-unsigned>>
    end) :: binary, 0 :: size(rem(8 - rem(weightsLen + 4, 8), 8))>>)
  end

  @spec weightuivARB(weights) :: :ok when weights: [integer()]
  def weightuivARB(weights) do
    weightsLen = length(weights)
    cast(5603, <<weightsLen :: 32-native-unsigned, (for c <- weights do
      <<c :: 32-native-unsigned>>
    end) :: binary, 0 :: size(rem(1 + weightsLen, 2) * 32)>>)
  end

  @spec weightusvARB(weights) :: :ok when weights: [integer()]
  def weightusvARB(weights) do
    weightsLen = length(weights)
    cast(5602, <<weightsLen :: 32-native-unsigned, (for c <- weights do
      <<c :: 16-native-unsigned>>
    end) :: binary, 0 :: size(rem(8 - rem(weightsLen * 2 + 4, 8), 8))>>)
  end

  @spec windowPos2d(x, y) :: :ok when x: float(), y: float()
  def windowPos2d(x, y), do: cast(5415, <<x :: 64-native-float, y :: 64-native-float>>)

  @spec windowPos2dv(v) :: :ok when v: {x :: float(), y :: float()}
  def windowPos2dv({x, y}), do: windowPos2d(x, y)

  @spec windowPos2f(x, y) :: :ok when x: float(), y: float()
  def windowPos2f(x, y), do: cast(5416, <<x :: 32-native-float, y :: 32-native-float>>)

  @spec windowPos2fv(v) :: :ok when v: {x :: float(), y :: float()}
  def windowPos2fv({x, y}), do: windowPos2f(x, y)

  @spec windowPos2i(x, y) :: :ok when x: integer(), y: integer()
  def windowPos2i(x, y), do: cast(5417, <<x :: 32-native-signed, y :: 32-native-signed>>)

  @spec windowPos2iv(v) :: :ok when v: {x :: integer(), y :: integer()}
  def windowPos2iv({x, y}), do: windowPos2i(x, y)

  @spec windowPos2s(x, y) :: :ok when x: integer(), y: integer()
  def windowPos2s(x, y), do: cast(5418, <<x :: 16-native-signed, y :: 16-native-signed>>)

  @spec windowPos2sv(v) :: :ok when v: {x :: integer(), y :: integer()}
  def windowPos2sv({x, y}), do: windowPos2s(x, y)

  @spec windowPos3d(x, y, z) :: :ok when x: float(), y: float(), z: float()
  def windowPos3d(x, y, z), do: cast(5419, <<x :: 64-native-float, y :: 64-native-float, z :: 64-native-float>>)

  @spec windowPos3dv(v) :: :ok when v: {x :: float(), y :: float(), z :: float()}
  def windowPos3dv({x, y, z}), do: windowPos3d(x, y, z)

  @spec windowPos3f(x, y, z) :: :ok when x: float(), y: float(), z: float()
  def windowPos3f(x, y, z), do: cast(5420, <<x :: 32-native-float, y :: 32-native-float, z :: 32-native-float>>)

  @spec windowPos3fv(v) :: :ok when v: {x :: float(), y :: float(), z :: float()}
  def windowPos3fv({x, y, z}), do: windowPos3f(x, y, z)

  @spec windowPos3i(x, y, z) :: :ok when x: integer(), y: integer(), z: integer()
  def windowPos3i(x, y, z), do: cast(5421, <<x :: 32-native-signed, y :: 32-native-signed, z :: 32-native-signed>>)

  @spec windowPos3iv(v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer()}
  def windowPos3iv({x, y, z}), do: windowPos3i(x, y, z)

  @spec windowPos3s(x, y, z) :: :ok when x: integer(), y: integer(), z: integer()
  def windowPos3s(x, y, z), do: cast(5422, <<x :: 16-native-signed, y :: 16-native-signed, z :: 16-native-signed>>)

  @spec windowPos3sv(v) :: :ok when v: {x :: integer(), y :: integer(), z :: integer()}
  def windowPos3sv({x, y, z}), do: windowPos3s(x, y, z)

  # Private Functions

  defp unquote(:"-areTexturesResident/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-callLists/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-clearBufferfv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-clearBufferiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-clearBufferuiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-compileShaderIncludeARB/2-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-convolutionParameterf/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-convolutionParameteri/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-createShaderProgramv/2-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-debugMessageControlARB/5-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-deleteBuffers/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-deleteFramebuffers/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-deleteProgramPipelines/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-deleteProgramsARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-deleteQueries/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-deleteRenderbuffers/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-deleteSamplers/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-deleteTextures/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-deleteTransformFeedbacks/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-deleteVertexArrays/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-depthRangeArrayv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-drawBuffers/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-fogfv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-fogiv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-getActiveUniformsiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-getUniformIndices/2-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-lightModelfv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-lightModeliv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-lightfv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-lightiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-materialfv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-materialiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-matrixIndexubvARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-matrixIndexuivARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-matrixIndexusvARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-multiDrawArrays/3-lbc$^0/2-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-multiDrawArrays/3-lbc$^1/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-patchParameterfv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-pointParameterfv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-pointParameteriv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-prioritizeTextures/2-lbc$^0/2-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-prioritizeTextures/2-lbc$^1/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform1dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform1fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform1iv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform1uiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform2dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform2fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform2iv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform2uiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform3dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform3fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform3iv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform3uiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform4dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform4fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform4iv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniform4uiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix2dv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix2fv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix2x3dv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix2x3fv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix2x4dv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix2x4fv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix3dv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix3fv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix3x2dv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix3x2fv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix3x4dv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix3x4fv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix4dv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix4fv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix4x2dv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix4x2fv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix4x3dv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-programUniformMatrix4x3fv/4-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-samplerParameterIiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-samplerParameterIuiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-samplerParameterfv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-samplerParameteriv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-scissorArrayv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-shaderBinary/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-shaderSource/2-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-shaderSourceARB/2-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-texEnvfv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-texEnviv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-texGendv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-texGenfv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-texGeniv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-texParameterIiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-texParameterIuiv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-texParameterfv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-texParameteriv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-transformFeedbackVaryings/3-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-uniform1dv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform1fv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform1iv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform1uiv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform2dv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform2fv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform2iv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform2uiv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform3dv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform3fv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform3iv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform3uiv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform4dv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform4fv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform4iv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniform4uiv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix2dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix2fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix2x3dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix2x3fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix2x4dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix2x4fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix3dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix3fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix3x2dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix3x2fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix3x4dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix3x4fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix4dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix4fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix4x2dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix4x2fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix4x3dv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformMatrix4x3fv/3-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-uniformSubroutinesuiv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-viewportArrayv/2-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-weightbvARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-weightdvARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-weightfvARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-weightivARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-weightsvARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-weightubvARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-weightuivARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-weightusvARB/1-lbc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp rec(op) do
    receive do
    {:_egl_result_, res} ->
        res
      {:_egl_error_, ^op, res} ->
        error({:error, res, op})
      {:_egl_error_, other, res} ->
        err = :io_lib.format('~p in op: ~p', [res, other])
        :error_logger.error_report([{:gl, :error}, {:message, :lists.flatten(err)}])
        rec(op)
    end
  end
end
