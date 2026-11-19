import axios from './axios';

export const knowledgeBaseMemberApi = {
  join: (params: Record<string, unknown>) => axios.post('/ai/api/knowledgeBaseMember/join', params),
  leave: (params: Record<string, unknown>) => axios.post('/ai/api/knowledgeBaseMember/leave', params),
  remove: (params: Record<string, unknown>) => axios.post('/ai/api/knowledgeBaseMember/remove', params),
  members: (params: Record<string, unknown>) => axios.post('/ai/api/knowledgeBaseMember/members', params),
  joinedIds: (params: Record<string, unknown>) => axios.post('/ai/api/knowledgeBaseMember/joinedIds', params),
};
