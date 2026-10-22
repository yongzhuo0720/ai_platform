export interface NewApiFormState {
  iconName: string;
  iconUrl: string;
  videoName: string;
  videoUrl: string;
  apiType: string;
  name: string;
  description: string;
  tags: string[];
  markdown: string;
}

export type TagError = "empty" | "duplicate" | "limit";

export interface AddTagResult {
  tags: string[];
  error: TagError | null;
}

export interface InsertTextResult {
  value: string;
  cursor: number;
}

export const createEmptyFormState = (): NewApiFormState => ({
  iconName: "",
  iconUrl: "",
  videoName: "",
  videoUrl: "",
  apiType: "",
  name: "",
  description: "",
  tags: [],
  markdown: "",
});

const normalizeTag = (value: string) => value.trim();

const toComparableState = (state: NewApiFormState) =>
  JSON.stringify({
    iconName: state.iconName,
    videoName: state.videoName,
    apiType: state.apiType.trim(),
    name: state.name.trim(),
    description: state.description.trim(),
    tags: state.tags,
    markdown: state.markdown.trim(),
  });

export const addTag = (currentTags: string[], draftTag: string): AddTagResult => {
  const nextTag = normalizeTag(draftTag);

  if (!nextTag) {
    return {
      tags: currentTags,
      error: "empty",
    };
  }

  if (currentTags.includes(nextTag)) {
    return {
      tags: currentTags,
      error: "duplicate",
    };
  }

  if (currentTags.length >= 3) {
    return {
      tags: currentTags,
      error: "limit",
    };
  }

  return {
    tags: [...currentTags, nextTag],
    error: null,
  };
};

export const hasFormDraft = (state: NewApiFormState) =>
  Boolean(
    state.iconName ||
      state.iconUrl ||
      state.videoName ||
      state.videoUrl ||
      state.apiType.trim() ||
      state.name.trim() ||
      state.description.trim() ||
      state.tags.length ||
      state.markdown.trim(),
  );

export const hasFormChanges = (
  currentState: NewApiFormState,
  baselineState: NewApiFormState,
) => toComparableState(currentState) !== toComparableState(baselineState);

export const cloneFormState = (state: NewApiFormState): NewApiFormState => ({
  ...state,
  tags: [...state.tags],
});

export const insertTextAtRange = (
  source: string,
  insertedText: string,
  selectionStart: number,
  selectionEnd: number,
): InsertTextResult => {
  const prefix = source.slice(0, selectionStart);
  const suffix = source.slice(selectionEnd);
  const value = `${prefix}${insertedText}${suffix}`;

  return {
    value,
    cursor: prefix.length + insertedText.length,
  };
};

const imageUrlPattern =
  /^(blob:[^\s]+|data:image\/[a-zA-Z0-9.+-]+;base64,[^\s]+|https?:\/\/[^\s]+\.(?:png|jpe?g|gif|webp|svg)(?:\?[^\s]*)?)$/;

export const transformMarkdownImageUrls = (source: string) =>
  source
    .split("\n")
    .map((line) => {
      const trimmedLine = line.trim();
      return imageUrlPattern.test(trimmedLine) ? `![](${trimmedLine})` : line;
    })
    .join("\n");
