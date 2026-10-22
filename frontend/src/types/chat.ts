export interface ChatMessage {
  id: string;
  conversation_id: string;
  inputs: Record<string, unknown>;
  query: string;
  message_files?: Array<{
    id: string;
    type: string;
    url: string;
    belongs_to: string;
  }>;
  answer: string;
  created_at: number;
  feedback?: {
    rating: 'like' | 'dislike' | null;
  };
  retriever_resources?: Array<{
    dataset_id: string;
    dataset_name: string;
    document_id: string;
    document_name: string;
    data_source_type: string;
    segment_id: string;
    score: number;
    hit_count: number;
    word_count: number;
    segment_position: number;
    index_node_hash: string;
    content: string;
  }>;
  agent_thoughts?: Array<{
    id: string;
    position: number;
    thought: string;
    observation: string;
    tool: string;
    tool_input: Record<string, unknown>;
    created_at: number;
    message_files?: Array<{
      id: string;
      type: string;
      url: string;
      belongs_to: string;
    }>;
  }>;
  status?: string;
  error?: string;
}

export interface ChatMessageResponse {
  message_id: string;
  conversation_id: string;
  answer: string;
  created_at: number;
  id?: string;
  task_id?: string;
  mode?: string;
}

export interface Conversation {
  id: string;
  name: string;
  inputs: Record<string, unknown>;
  status: string;
  introduction: string;
  created_at: number;
  updated_at: number;
}

export interface ConversationListResponse {
  data: Conversation[];
  has_more: boolean;
  limit: number;
}

export interface ConversationMessagesResponse {
  data: ChatMessage[];
  has_more: boolean;
  limit: number;
}

export interface StopChatResponse {
  result: string;
}

export interface RenameConversationParams {
  name: string;
  auto_generate?: boolean;
  user: string;
}

export interface MessageFeedbackParams {
  rating: 'like' | 'dislike';
  user: string;
  content?: string;
}

export interface MessageFeedbackResponse {
  result: string;
  id?: string;
  rating?: 'like' | 'dislike';
  content?: string;
  created_at?: number;
  updated_at?: number;
}

export interface TextInputControl {
  label: string;
  variable: string;
  required: boolean;
  default: string;
}

export interface ParagraphControl {
  label: string;
  variable: string;
  required: boolean;
  default: string;
}

export interface SelectControl {
  label: string;
  variable: string;
  required: boolean;
  default: string;
  options?: string[];
}

export type UserInputFieldType = 'text-input' | 'paragraph' | 'select' | 'file-upload';

export interface FileUploadControl {
  label: string;
  variable: string;
  required?: boolean;
  allowed_file_types?: string[];
  allowed_file_extensions?: string[];
  allowed_file_upload_methods?: Array<'local_file' | 'remote_url'>;
}

export interface UserInputFormItem {
  'text-input'?: TextInputControl;
  paragraph?: ParagraphControl;
  select?: SelectControl;
  'file-upload'?: FileUploadControl;
  /** Dify 某些版本使用下划线格式 */
  file_upload?: FileUploadControl;
}

export interface NormalizedUserInputField {
  type: UserInputFieldType;
  label: string;
  variable: string;
  required: boolean;
  defaultValue: string;
  options?: string[];
  /** 文件类型变量允许的文件类型（仅 file-upload 类型有效） */
  allowed_file_types?: string[];
  /** 文件类型变量允许的上传方式（仅 file-upload 类型有效） */
  allowed_file_upload_methods?: Array<'local_file' | 'remote_url'>;
}

export interface AppParametersResponse {
  opening_statement: string;
  suggested_questions: string[];
  suggested_questions_after_answer: {
    enabled: boolean;
  };
  speech_to_text: {
    enabled: boolean;
  };
  text_to_speech: {
    enabled: boolean;
    voice: string;
    language: string;
    autoPlay: string;
  };
  retriever_resource: {
    enabled: boolean;
  };
  annotation_reply: {
    enabled: boolean;
  };
  more_like_this: {
    enabled: boolean;
  };
  sensitive_word_avoidance: {
    enabled: boolean;
  };
  user_input_form: UserInputFormItem[];
  file_upload: {
    image?: {
      enabled: boolean;
      number_limits: number;
      detail: string;
      transfer_methods: string[];
    };
  };
  system_parameters: {
    file_size_limit: number;
    image_file_size_limit: number;
    audio_file_size_limit: number;
    video_file_size_limit: number;
    workflow_file_upload_limit: number;
  };
}
