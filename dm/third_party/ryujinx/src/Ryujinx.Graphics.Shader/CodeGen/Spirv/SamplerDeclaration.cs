using Spv.Generator;

namespace Ryujinx.Graphics.Shader.CodeGen.Spirv
{
    readonly struct SamplerDeclaration
    {
        public readonly Instruction ImageType;
        public readonly Instruction SampledImageType;
        public readonly Instruction SampledImagePointerType;
        public readonly Instruction Image;
        public readonly bool IsIndexed;
        // PEANUTS: an array the shader indexes with a value it computes (an array of handles from a buffer, BindlessToArray: at most 256 elements; the pools of
        // every texture and sampler are longer and take the index of a handle, one for a whole draw). Two pixels of one quad may pick different elements,
        // so its reads carry the NonUniform decoration whatever the host capability says (Instructions.cs GenerateSampledImageLoad)
        public readonly bool IsShaderIndexed;
        public const int ShaderIndexedMaxLength = 256;

        public SamplerDeclaration(
            Instruction imageType,
            Instruction sampledImageType,
            Instruction sampledImagePointerType,
            Instruction image,
            bool isIndexed,
            bool isShaderIndexed = false) // PEANUTS
        {
            ImageType = imageType;
            SampledImageType = sampledImageType;
            SampledImagePointerType = sampledImagePointerType;
            Image = image;
            IsIndexed = isIndexed;
            IsShaderIndexed = isShaderIndexed; // PEANUTS
        }
    }
}
