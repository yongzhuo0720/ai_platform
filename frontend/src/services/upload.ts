export interface UploadResponse {
  url?: string;
}

export const extractUploadUrl = (payload: UploadResponse) => {
  if (!payload.url || typeof payload.url !== "string") {
    throw new Error("Upload response must include a url field.");
  }

  return payload.url;
};

export const uploadMarkdownImage = async (file: File) => {
  const formData = new FormData();
  formData.append("file", file);

  const response = await fetch("/api/uploads/markdown-image", {
    method: "POST",
    body: formData,
  });

  if (!response.ok) {
    throw new Error("Markdown image upload failed.");
  }

  const payload = (await response.json()) as UploadResponse;
  return extractUploadUrl(payload);
};
